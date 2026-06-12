package org.example.sqlite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * [SqliteDataSource]/[SqliteConnection] — JDBC 형 온디맨드 워커.
 *
 * 핵심 검증: (1) 그때그때 spawn 되는 커넥션 수명주기, (2) 동시 getConnection (내부 spawn 직렬화),
 * (3) **다중 커넥션 동시 writer** — WAL write 락(JvmVfsLocks=JVM 락) + busy 재시도가 직렬화를 담당.
 * (과거 "다중 writer 커밋 유실" 경고는 함정 3 진단 전 측정 — StatelessBulkMemory 이후 재판정)
 */
class SqliteDataSourceTest {

    @Test
    fun `기본 - 온디맨드 커넥션 수명주기`() {
        val dir = Files.createTempDirectory("sqliteds")
        SqliteDataSource(dir, "basic.db").use { ds ->
            ds.getConnection().use { con ->
                con.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)")
                con.execute("INSERT INTO t(name) VALUES ('alice')")
                assertEquals(1L, con.queryLong("SELECT count(*) FROM t"))
                assertEquals("wal", con.queryText("PRAGMA journal_mode"))
            }
            // 첫 커넥션 close 후 새 커넥션 — 워커가 새로 생성됨
            ds.getConnection().use { con ->
                con.execute("INSERT INTO t(name) VALUES ('bob')")
                assertEquals(2L, con.queryLong("SELECT count(*) FROM t"))
                assertEquals("ok", con.queryText("PRAGMA integrity_check"))
            }
        }
    }

    @Test
    fun `동시 getConnection - VT 8개가 각자 커넥션 생성+사용+반납`() {
        val dir = Files.createTempDirectory("sqliteds")
        SqliteDataSource(dir, "conc.db").use { ds ->
            ds.getConnection().use { it.execute("CREATE TABLE t(v INTEGER)") }
            Executors.newVirtualThreadPerTaskExecutor().use { ex ->
                val fs = (1..8).map { k ->
                    ex.submit {
                        ds.getConnection().use { con ->          // 동시 호출 — 내부 직렬화 검증
                            repeat(10) { con.execute("INSERT INTO t(v) VALUES ($k)") }
                            check(con.queryLong("SELECT count(*) FROM t WHERE v=$k") == 10L)
                        }
                    }
                }
                fs.forEach { it.get() }
            }
            ds.getConnection().use { con ->
                assertEquals(80L, con.queryLong("SELECT count(*) FROM t"))
                assertEquals("ok", con.queryText("PRAGMA integrity_check"))
            }
        }
    }

    @Test
    fun `다중 커넥션 동시 writer - WAL 자체 락으로 직렬화`() {
        val dir = Files.createTempDirectory("sqliteds")
        SqliteDataSource(dir, "mw.db").use { ds ->
            ds.getConnection().use { it.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, tag INTEGER)") }
            val writers = 4; val perWriter = 50
            val cons = (1..writers).map { ds.getConnection() }   // 미리 열고
            try {
                Executors.newVirtualThreadPerTaskExecutor().use { ex ->
                    val fs = ArrayList<Future<*>>()
                    cons.forEachIndexed { i, con ->
                        fs += ex.submit {
                            repeat(perWriter) { con.execute("INSERT INTO t(tag) VALUES ($i)") }
                        }
                    }
                    fs.forEach { it.get() }
                }
            } finally {
                cons.forEach { it.close() }
            }
            ds.getConnection().use { con ->
                assertEquals((writers * perWriter).toLong(), con.queryLong("SELECT count(*) FROM t"))
                assertEquals("ok", con.queryText("PRAGMA integrity_check"))
            }
        }
    }

    @Test
    fun `cancel - 실행 중 무한 쿼리를 sqlite3_interrupt 로 중단, 커넥션은 재사용 가능`() {
        val dir = Files.createTempDirectory("sqliteds")
        SqliteDataSource(dir, "cancel.db").use { ds ->
            ds.getConnection().use { con ->
                con.execute("CREATE TABLE t(v INTEGER)")
                Executors.newVirtualThreadPerTaskExecutor().use { ex ->
                    val f = ex.submit(java.util.concurrent.Callable {
                        try {
                            // 무한 재귀 CTE — cancel 없이는 끝나지 않는다
                            con.queryLong("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c) SELECT count(*) FROM c")
                            null as Throwable?
                        } catch (t: Throwable) { t }
                    })
                    Thread.sleep(500)            // 쿼리가 실행에 진입할 시간
                    val t0 = System.nanoTime()
                    con.cancel()
                    val err = f.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                    org.junit.jupiter.api.Assertions.assertNotNull(err, "무한 쿼리가 중단되지 않음")
                    assertTrue(err!!.message!!.contains("rc=9")) { "rc=9(INTERRUPT) 아님: ${err.message}" }
                    assertTrue(elapsedMs < 5_000) { "중단 지연 ${elapsedMs}ms" }
                }
                // 취소 후에도 커넥션 정상
                con.execute("INSERT INTO t(v) VALUES (1)")
                assertEquals(1L, con.queryLong("SELECT count(*) FROM t"))
                assertEquals("ok", con.queryText("PRAGMA integrity_check"))
            }
        }
    }

    @Test
    fun `단독 소유 가드 - 같은 DB 중복 오픈 거부, 해제 후 재오픈 허용`() {
        val dir = Files.createTempDirectory("sqliteds")
        val ds1 = SqliteDataSource(dir, "guard.db")
        try {
            // 같은 파일 중복 오픈 → 거부 (같은 JVM 중복 런타임도 OS 락 도메인으로 잡힘)
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                SqliteDataSource(dir, "guard.db")
            }
            // SqliteWal 도 같은 가드를 공유
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                SqliteWal.open(dir, "guard.db")
            }
            // 다른 파일은 무관
            SqliteDataSource(dir, "other.db").close()
        } finally {
            ds1.close()
        }
        // 해제 후 재오픈 OK
        SqliteDataSource(dir, "guard.db").close()
    }

    @Test
    fun `DataSource close - 열린 커넥션 정리 + 재오픈 영속성`() {
        val dir = Files.createTempDirectory("sqliteds")
        val ds = SqliteDataSource(dir, "persist.db")
        val con = ds.getConnection()
        con.execute("CREATE TABLE t(v INTEGER)")
        con.execute("INSERT INTO t(v) VALUES (42)")
        ds.close()                                  // con 을 닫지 않았어도 정리돼야 함
        SqliteDataSource(dir, "persist.db").use { ds2 ->
            ds2.getConnection().use { c ->
                assertEquals(42L, c.queryLong("SELECT v FROM t"))
            }
        }
    }
}
