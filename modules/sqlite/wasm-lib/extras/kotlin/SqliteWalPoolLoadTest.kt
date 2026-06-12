package org.example.sqlite

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 부하 테스트: 무폴링 블로킹 채널(req/done)로 전환된 [SqliteWalPool] 이 고동시성에서 견고한가.
 *
 * 풀 8 워커 · writer 6 · reader 4 동시. writer 직렬화(WAL 1-writer) + reader 동시 하에서
 * 유실 0(총계·태그별 몫 정확), reader 단조 증가, 데드락 없음(블로킹 futex hang 회귀 방지)을 확인.
 */
class SqliteWalPoolLoadTest {

    @Test
    fun `부하 - 8워커 동시 writer+reader 무유실`() {
        val dir = Files.createTempDirectory("walpool-load")
        SqliteWalPool.open(dir, "load.db", size = 8).use { pool ->
            pool.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, tag INTEGER)")

            val WRITERS = 6
            val PER = 200
            val READERS = 4
            val READS = 300
            val total = (WRITERS * PER).toLong()
            val errors = ConcurrentLinkedQueue<Throwable>()
            val start = CountDownLatch(1)
            val maxSeen = AtomicLong(0)

            val writers = (0 until WRITERS).map { tid ->
                Thread.ofVirtual().unstarted {
                    try {
                        start.await()
                        repeat(PER) { pool.exec("INSERT INTO t(tag) VALUES ($tid)") }
                    } catch (e: Throwable) { errors += e }
                }.apply { name = "w$tid"; start() }
            }
            val readers = (0 until READERS).map { rid ->
                Thread.ofVirtual().unstarted {
                    try {
                        start.await()
                        var last = 0L
                        repeat(READS) {
                            val c = pool.queryLong("SELECT count(*) FROM t")
                            if (c < last || c > total) errors += AssertionError("r$rid 비정상 카운트 $c (이전 $last, 상한 $total)")
                            last = c
                            maxSeen.updateAndGet { m -> maxOf(m, c) }
                        }
                    } catch (e: Throwable) { errors += e }
                }.apply { name = "r$rid"; start() }
            }

            start.countDown()
            val all = writers + readers
            val t0 = System.currentTimeMillis()
            all.forEach { it.join(120_000) }
            val ms = System.currentTimeMillis() - t0
            println("LOAD: ${WRITERS}w×$PER + ${READERS}r×$READS in ${ms}ms, maxSeen=${maxSeen.get()}")

            assertTrue(all.none { it.isAlive }, "스레드 미완료 (블로킹 데드락 의심): ${all.filter { it.isAlive }.map { it.name }}")
            assertTrue(errors.isEmpty(), "오류 ${errors.size}건: ${errors.toList().take(5)}")

            // 유실 0: 총계 + 태그별 몫
            assertEquals(total, pool.queryLong("SELECT count(*) FROM t"), "유실 없이 전부 삽입")
            for (tid in 0 until WRITERS) {
                assertEquals(PER.toLong(), pool.queryLong("SELECT count(*) FROM t WHERE tag=$tid"), "tag=$tid 몫")
            }
            // PK 무결성: id distinct 수 == 총계
            assertEquals(total, pool.queryLong("SELECT count(DISTINCT id) FROM t"), "PK distinct == 총계")
        }
    }
}
