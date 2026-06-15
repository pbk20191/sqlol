package org.example.sqlite.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * 바인딩 (= JDBC PreparedStatement 대응) — [SqlitePreparedStatement](명시적, 캐시 밖 사용자 stmt) 와
 * vararg 표면(LRU 캐시 stmt 재사용 — SQL 상수화로 캐시 적중 100%).
 *
 * 핵심 검증: (1) 전 타입 라운드트립 + TRANSIENT 스크래치, (2) 인젝션 차단(값이 SQL 로 안 섞임),
 * (3) stmt 재사용/재바인딩 + 영향 행 수, (4) 수명(이중 close/커넥션 선닫힘/use-after-close),
 * (5) SqliteWal 공유 큐 워크스틸링에서 vararg 바인딩 안전(워커별 캐시 적중).
 */
class SqlitePreparedStatementTest {

    @Test
    fun `타입 라운드트립 - long double text blob null + 빈 문자열`() {
        val dir = Files.createTempDirectory("sqliteps")
        SqliteDataSource(dir, "types.db").use { ds ->
            ds.getConnection().use { con ->
                con.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, i INTEGER, r REAL, s TEXT, b BLOB, n TEXT)")
                con.prepare("INSERT INTO t(i,r,s,b,n) VALUES (?,?,?,?,?)").use { ps ->
                    assertEquals(1L, ps.setAll(42L, 2.5, "héllo — 한글", byteArrayOf(1, 2, 3), null).execute())
                }
                assertEquals(42L, con.queryLong("SELECT i FROM t"))
                assertEquals("héllo — 한글", con.queryText("SELECT s FROM t"))
                assertEquals("real", con.queryText("SELECT typeof(r) FROM t"))
                assertEquals(1L, con.queryLong("SELECT count(*) FROM t WHERE r = ?", 2.5))
                assertEquals("010203", con.queryText("SELECT lower(hex(b)) FROM t"))
                assertEquals("null", con.queryText("SELECT typeof(n) FROM t"))
                // 빈 문자열은 NULL 이 아니라 text 여야 한다 (scratch(0) 가 유효 포인터를 줘야 함)
                con.execute("INSERT INTO t(s) VALUES (?)", "")
                assertEquals("text", con.queryText("SELECT typeof(s) FROM t WHERE id = 2"))
                assertEquals(0L, con.queryLong("SELECT length(s) FROM t WHERE id = 2"))
                // Int/Boolean/Float 변환 바인딩
                con.execute("INSERT INTO t(i, r) VALUES (?, ?)", 7, 1.5f)
                assertEquals(7L, con.queryLong("SELECT i FROM t WHERE id = 3"))
                con.execute("INSERT INTO t(i) VALUES (?)", true)
                assertEquals(1L, con.queryLong("SELECT i FROM t WHERE id = 4"))
            }
        }
    }

    @Test
    fun `인젝션 차단 - 값은 SQL 로 섞이지 않는다`() {
        val dir = Files.createTempDirectory("sqliteps")
        SqliteDataSource(dir, "inj.db").use { ds ->
            ds.getConnection().use { con ->
                con.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, s TEXT)")
                val evil = "x'); DROP TABLE t;--"
                con.execute("INSERT INTO t(s) VALUES (?)", evil)
                assertEquals(1L, con.queryLong("SELECT count(*) FROM t WHERE s = ?", evil))
                assertEquals(evil, con.queryText("SELECT s FROM t WHERE s = ?", evil))
                assertEquals("ok", con.queryText("PRAGMA integrity_check"))   // 테이블 생존
            }
        }
    }

    @Test
    fun `재사용 - 한 stmt 로 100회 재바인딩 + 영향 행 수`() {
        val dir = Files.createTempDirectory("sqliteps")
        SqliteDataSource(dir, "reuse.db").use { ds ->
            ds.getConnection().use { con ->
                con.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, v INTEGER, tag TEXT)")
                con.prepare("INSERT INTO t(v, tag) VALUES (?, ?)").use { ps ->
                    for (i in 1..100) assertEquals(1L, ps.setAll(i.toLong(), "tag-$i").execute())
                }
                assertEquals(100L, con.queryLong("SELECT count(*) FROM t"))
                con.prepare("SELECT count(*) FROM t WHERE v <= ?").use { ps ->
                    assertEquals(50L, ps.set(1, 50L).queryLong())
                    assertEquals(100L, ps.set(1, 1000L).queryLong())   // 재바인딩
                }
                con.prepare("UPDATE t SET tag = ? WHERE v <= ?").use { ps ->
                    assertEquals(30L, ps.setAll("bulk", 30L).execute())   // changes = 30
                }
            }
        }
    }

    @Test
    fun `수명 - 이중 close, use-after-close, 커넥션 선닫힘`() {
        val dir = Files.createTempDirectory("sqliteps")
        SqliteDataSource(dir, "life.db").use { ds ->
            ds.getConnection().use { con ->
                con.execute("CREATE TABLE t(id INTEGER PRIMARY KEY)")
                val ps = con.prepare("INSERT INTO t DEFAULT VALUES")
                ps.execute()
                ps.close()
                ps.close()   // 멱등
                assertThrows(IllegalStateException::class.java) { ps.execute() }
                assertThrows(IllegalStateException::class.java) { ps.set(1, 1L) }
            }
            // 열린 stmt 를 남긴 채 커넥션 close → 워커 종료가 finalize (누수/행 없이 내려가야 함)
            val con2 = ds.getConnection()
            val leaked = con2.prepare("SELECT count(*) FROM t WHERE id <= ?")
            assertEquals(1L, leaked.set(1, 999L).queryLong())
            con2.close()
            leaked.close()   // 커넥션 닫힌 뒤의 close 는 no-op (예외 없음)
            // DB 는 멀쩡해야 함
            ds.getConnection().use { con3 ->
                assertEquals("ok", con3.queryText("PRAGMA integrity_check"))
            }
        }
    }

    @Test
    fun `SqliteWal vararg - 공유 큐 워크스틸링에서 바인딩 안전`() {
        val dir = Files.createTempDirectory("sqliteps")
        SqliteWal.open(dir, "wal.db", readers = 3).use { db ->
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, v INTEGER)")
            for (i in 1..100) db.exec("INSERT INTO t(v) VALUES (?)", i.toLong())
            // 같은 SQL 텍스트 + 다른 바인딩을 reader 풀에 동시 제출 — 어느 워커가 집어도 자기 캐시 stmt
            Executors.newVirtualThreadPerTaskExecutor().use { ex ->
                (1..100).map { k ->
                    ex.submit<Long> { db.queryLong("SELECT count(*) FROM t WHERE v <= ?", k.toLong()) }
                        .let { f -> k.toLong() to f }
                }.forEach { (k, f) -> assertEquals(k, f.get()) }
            }
            assertEquals("ok", db.queryText("PRAGMA integrity_check"))
        }
    }
}
