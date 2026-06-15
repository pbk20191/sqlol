package org.example.sqlite.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * 다중 행/열 materialized 결과 ([SqliteRow]) — JDBC ResultSet 대응의 코어스닝 형태
 * (쿼리 1건 = 워커 태스크 1건 = 전 행, 행당 큐 왕복 없음).
 *
 * 핵심 검증: (1) storage class 디코드 + 컬럼명 접근, (2) 빈 결과/대량 결과, (3) 형변환 근사,
 * (4) PreparedStatement.query 재바인딩, (5) SqliteWal reader 풀에서 동시 query (워크스틸링 + 스냅샷).
 */
class SqliteQueryRowsTest {

    @Test
    fun `다중 행 다중 열 - storage class 와 컬럼명`() {
        val dir = Files.createTempDirectory("sqliterows")
        SqliteDataSource(dir, "rows.db").use { ds ->
            ds.getConnection().use { con ->
                con.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT, score REAL, data BLOB)")
                con.execute("INSERT INTO t(name, score, data) VALUES (?,?,?)", "alice", 1.5, byteArrayOf(9, 8))
                con.execute("INSERT INTO t(name, score, data) VALUES (?,?,?)", "bob", 2.5, null)
                con.execute("INSERT INTO t(name, score, data) VALUES (?,?,?)", null, null, byteArrayOf(7))

                val rows = con.query("SELECT id, name, score, data FROM t ORDER BY id")
                assertEquals(3, rows.size)
                assertEquals(4, rows[0].columnCount)
                assertEquals(listOf("id", "name", "score", "data"), (0..3).map { rows[0].columnName(it) })

                // 저장 클래스 그대로 (value) — INTEGER→Long, TEXT→String, FLOAT→Double, BLOB→ByteArray
                assertTrue(rows[0].value("id") is Long)
                assertTrue(rows[0].value("name") is String)
                assertTrue(rows[0].value("score") is Double)
                assertTrue(rows[0].value("data") is ByteArray)

                assertEquals(1L, rows[0].getLong("id"))
                assertEquals("alice", rows[0].getText("name"))
                assertEquals(1.5, rows[0].getDouble("score"))
                assertArrayEquals(byteArrayOf(9, 8), rows[0].getBlob("data"))

                // NULL 처리: isNull true + get* 는 sqlite 시맨틱 (0/""/빈 배열)
                assertTrue(rows[1].isNull("data"))
                assertArrayEquals(ByteArray(0), rows[1].getBlob("data"))
                assertTrue(rows[2].isNull("name"))
                assertEquals("", rows[2].getText("name"))
                assertEquals(0L, rows[2].getLong("score"))
            }
        }
    }

    @Test
    fun `빈 결과와 대량 결과 - 코어스닝 1태스크`() {
        val dir = Files.createTempDirectory("sqliterows")
        SqliteDataSource(dir, "bulk.db").use { ds ->
            ds.getConnection().use { con ->
                con.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, v INTEGER)")
                assertEquals(emptyList<SqliteRow>(), con.query("SELECT * FROM t"))

                con.execute("BEGIN")
                for (i in 1..1000) con.execute("INSERT INTO t(v) VALUES (?)", i.toLong())
                con.execute("COMMIT")

                val rows = con.query("SELECT id, v FROM t ORDER BY id")
                assertEquals(1000, rows.size)
                rows.forEachIndexed { i, r ->
                    assertEquals((i + 1).toLong(), r.getLong("id"))
                    assertEquals((i + 1).toLong(), r.getLong("v"))
                }
                // 바인딩 + 부분 결과
                assertEquals(10, con.query("SELECT v FROM t WHERE v <= ? ORDER BY v", 10L).size)
            }
        }
    }

    @Test
    fun `형변환 근사 - sqlite 시맨틱`() {
        val dir = Files.createTempDirectory("sqliterows")
        SqliteDataSource(dir, "coerce.db").use { ds ->
            ds.getConnection().use { con ->
                con.execute("CREATE TABLE t(i INTEGER, s TEXT)")
                con.execute("INSERT INTO t VALUES (?, ?)", 42L, "7")
                val r = con.query("SELECT i, s FROM t")[0]
                assertEquals("42", r.getText("i"))     // Long → 텍스트
                assertEquals(7L, r.getLong("s"))       // 숫자 텍스트 → Long
                assertEquals(42.0, r.getDouble("i"))   // Long → Double
            }
        }
    }

    @Test
    fun `PreparedStatement query - 재바인딩으로 다른 결과`() {
        val dir = Files.createTempDirectory("sqliterows")
        SqliteDataSource(dir, "ps.db").use { ds ->
            ds.getConnection().use { con ->
                con.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, v INTEGER)")
                for (i in 1..20) con.execute("INSERT INTO t(v) VALUES (?)", i.toLong())
                con.prepare("SELECT id, v FROM t WHERE v <= ? ORDER BY v").use { ps ->
                    assertEquals(5, ps.set(1, 5L).query().size)
                    val all = ps.set(1, 100L).query()
                    assertEquals(20, all.size)
                    assertEquals(20L, all.last().getLong("v"))
                }
            }
        }
    }

    @Test
    fun `SqliteWal query - reader 풀 동시 다중 행`() {
        val dir = Files.createTempDirectory("sqliterows")
        SqliteWal.open(dir, "wal.db", readers = 3).use { db ->
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, v INTEGER)")
            for (i in 1..100) db.exec("INSERT INTO t(v) VALUES (?)", i.toLong())
            Executors.newVirtualThreadPerTaskExecutor().use { ex ->
                (1..40).map { k ->
                    k to ex.submit<List<SqliteRow>> { db.query("SELECT v FROM t WHERE v <= ? ORDER BY v", k.toLong()) }
                }.forEach { (k, f) ->
                    val rows = f.get()
                    assertEquals(k, rows.size)                       // 한 태스크 = 한 워커 = 일관 스냅샷
                    assertEquals(k.toLong(), rows.last().getLong(0))
                }
            }
        }
    }
}
