package org.example.sqlite

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M4: WAL 커넥션 풀 [SqliteWalPool] — 여러 스레드가 풀에서 워커를 빌려 같은 파일에 동시 접근.
 */
class SqliteWalPoolTest {

    @Test
    fun `WAL 풀 - 동시 writer + reader`() {
        val dir = Files.createTempDirectory("walpool")
        SqliteWalPool.open(dir, "app.db", size = 4).use { pool ->
            pool.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, tag INTEGER)")

            val WRITERS = 3
            val PER = 50
            val total = (WRITERS * PER).toLong()
            val errors = ConcurrentLinkedQueue<Throwable>()
            val start = CountDownLatch(1)

            val writers = (0 until WRITERS).map { tid ->
                Thread.ofVirtual().unstarted {
                    try {
                        start.await()
                        repeat(PER) { pool.exec("INSERT INTO t(tag) VALUES ($tid)") }
                    } catch (e: Throwable) { errors += e }
                }.apply { start() }
            }
            val reader = Thread.ofVirtual().unstarted {
                try {
                    start.await()
                    var last = 0L
                    repeat(80) {
                        val c = pool.queryLong("SELECT count(*) FROM t")
                        assertTrue(c in last..total, "비정상 카운트 $c (이전 $last)")  // 커밋분만, 단조 증가
                        last = c
                    }
                } catch (e: Throwable) { errors += e }
            }.apply { start() }

            start.countDown()
            writers.forEach { it.join(60_000) }
            reader.join(60_000)
            assertTrue(writers.none { it.isAlive } && !reader.isAlive, "스레드 미완료 (데드락 의심)")
            assertTrue(errors.isEmpty(), "오류: ${errors.toList()}")

            // 검증: 총 행 수 + 스레드별 몫
            assertEquals(total, pool.queryLong("SELECT count(*) FROM t"), "유실 없이 전부 삽입")
            for (tid in 0 until WRITERS) {
                assertEquals(PER.toLong(), pool.queryLong("SELECT count(*) FROM t WHERE tag=$tid"), "tag=$tid 몫")
            }
        }
    }

    @Test
    fun `queryText - 텍스트 결과 + 빈 결과 + integrity_check`() {
        val dir = Files.createTempDirectory("walpool-text")
        SqliteWalPool.open(dir, "txt.db", size = 2).use { pool ->
            pool.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)")
            pool.exec("INSERT INTO t(name) VALUES ('alice'),('밥'),('charlie')")
            assertEquals("alice", pool.queryText("SELECT name FROM t ORDER BY id LIMIT 1"), "첫 행 텍스트")
            assertEquals("밥", pool.queryText("SELECT name FROM t WHERE id=2"), "UTF-8 텍스트")
            assertEquals("", pool.queryText("SELECT name FROM t WHERE id=999"), "빈 결과 → \"\"")
            assertEquals("ok", pool.queryText("PRAGMA integrity_check"), "무결성")
            assertEquals(3L, pool.queryLong("SELECT count(*) FROM t"), "queryLong 회귀")
        }
    }
}
