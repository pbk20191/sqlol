package org.example.sqlite

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2 — 같은 호스트 DB 파일을 여러 SqliteDb(독립 WASM 인스턴스)가 서로 다른 스레드에서
 * 동시에 사용할 때의 안전성. DbCoordinator 의 read/write 락으로 조율된다 (롤백 저널 모드).
 */
class MultiConnTest {

    private fun newDbFile(prefix: String): Path = Files.createTempDirectory(prefix)

    @Test
    fun `멀티스레드 동시 쓰기 - 유실 없음, 무결성 유지`() {
        val dir = newDbFile("m2-writers")
        SqliteDb.openFile(dir, "db.sqlite").use { db ->
            db.exec("PRAGMA journal_mode=DELETE")
            db.exec("CREATE TABLE t(tid INTEGER, seq INTEGER)")
        }

        val threads = 6
        val perThread = 40
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)

        val workers = (0 until threads).map { tid ->
            Thread {
                try {
                    start.await()
                    SqliteDb.openFile(dir, "db.sqlite").use { db ->
                        repeat(perThread) { s ->
                            db.update("INSERT INTO t(tid, seq) VALUES (?, ?)", tid.toLong(), s.toLong())
                        }
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join() }

        assertTrue(errors.isEmpty(), "스레드 오류 발생: ${errors.toList()}")

        SqliteDb.openFile(dir, "db.sqlite").use { db ->
            assertEquals(
                (threads * perThread).toLong(),
                db.query("SELECT count(*) FROM t") { it.getLong(0) }.single(),
                "삽입된 전체 행 수가 일치해야 함 (유실 없음)",
            )
            assertEquals(listOf("ok"), db.query("PRAGMA integrity_check") { it.getText(0) })
            // 각 스레드가 자기 몫을 모두 기록했는지
            val perTid = db.query("SELECT tid, count(*) FROM t GROUP BY tid ORDER BY tid") {
                it.getLong(0) to it.getLong(1)
            }
            assertEquals((0 until threads).map { it.toLong() to perThread.toLong() }, perTid)
        }
    }

    @Test
    fun `쓰기 진행 중 동시 읽기 - 깨진 읽기 없음`() {
        val dir = newDbFile("m2-rw")
        SqliteDb.openFile(dir, "db.sqlite").use { db ->
            db.exec("PRAGMA journal_mode=DELETE")
            db.exec("CREATE TABLE t(x INTEGER)")
        }

        val total = 200
        val errors = ConcurrentLinkedQueue<Throwable>()
        val writerDone = AtomicBoolean(false)

        val writer = Thread {
            try {
                SqliteDb.openFile(dir, "db.sqlite").use { db ->
                    for (i in 1..total) db.update("INSERT INTO t VALUES (?)", i.toLong())
                }
            } catch (e: Throwable) {
                errors += e
            } finally {
                writerDone.set(true)
            }
        }

        val readers = (0 until 3).map {
            Thread {
                try {
                    SqliteDb.openFile(dir, "db.sqlite").use { db ->
                        var last = 0L
                        while (!writerDone.get()) {
                            val c = db.query("SELECT count(*) FROM t") { it.getLong(0) }.single()
                            // 커밋된 행 수만 보이므로 단조 증가해야 한다 (롤백되다 만 행이 보이면 실패)
                            assertTrue(c in last..total.toLong(), "비정상 카운트: $c (이전 $last)")
                            last = c
                        }
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }.apply { start() }
        }
        writer.start()
        writer.join()
        readers.forEach { it.join() }

        assertTrue(errors.isEmpty(), "오류 발생: ${errors.toList()}")
        SqliteDb.openFile(dir, "db.sqlite").use { db ->
            assertEquals(total.toLong(), db.query("SELECT count(*) FROM t") { it.getLong(0) }.single())
        }
    }

    @Test
    fun `트랜잭션 원자성 - 멀티스레드`() {
        val dir = newDbFile("m2-txn")
        SqliteDb.openFile(dir, "db.sqlite").use { db ->
            db.exec("PRAGMA journal_mode=DELETE")
            db.exec("CREATE TABLE t(tid INTEGER, n INTEGER)")
        }

        val threads = 5
        val txnPerThread = 20
        val errors = ConcurrentLinkedQueue<Throwable>()

        val workers = (0 until threads).map { tid ->
            Thread {
                try {
                    SqliteDb.openFile(dir, "db.sqlite").use { db ->
                        repeat(txnPerThread) { k ->
                            // 트랜잭션당 2행 삽입. 홀수번째는 롤백 → 0행 커밋되어야 함.
                            runCatching {
                                db.transaction {
                                    db.update("INSERT INTO t VALUES (?, ?)", tid.toLong(), k.toLong())
                                    db.update("INSERT INTO t VALUES (?, ?)", tid.toLong(), k.toLong())
                                    if (k % 2 == 1) error("의도된 롤백")
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }.apply { start() }
        }
        workers.forEach { it.join() }

        assertTrue(errors.isEmpty(), "오류 발생: ${errors.toList()}")
        SqliteDb.openFile(dir, "db.sqlite").use { db ->
            // 짝수 k (0,2,..,18) = 10개 트랜잭션 커밋 × 2행 = 20행 per thread
            val committed = (txnPerThread / 2) * 2
            assertEquals(listOf("ok"), db.query("PRAGMA integrity_check") { it.getText(0) })
            assertEquals(
                (threads * committed).toLong(),
                db.query("SELECT count(*) FROM t") { it.getLong(0) }.single(),
                "커밋된 트랜잭션의 행만 존재해야 함 (부분 커밋/유실 없음)",
            )
        }
    }
}
