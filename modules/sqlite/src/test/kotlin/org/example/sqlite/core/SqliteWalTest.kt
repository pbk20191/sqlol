package org.example.sqlite.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/**
 * [SqliteWal] — 풀 없는 single-writer + bounded-reader (순수 JVM pthread 부트스트랩, BUILD.md §9).
 *
 * caller 를 전부 가상 스레드로 돌려 검증한다. 워커 본체가 Java 라 채널/대기/직렬화가 j.u.c 이며,
 * JEP 491 JVM(JDK 24+) 전제로 워커·caller 모두 VT.
 */
class SqliteWalTest {

    @Test
    fun `기본 CRUD + WAL 확인`() {
        val dir = Files.createTempDirectory("sqlitewal")
        SqliteWal.open(dir, "basic.db", readers = 2).use { db ->
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)")
            db.exec("INSERT INTO t(name) VALUES ('alice')")
            db.exec("INSERT INTO t(name) VALUES ('bob')")
            assertEquals(2L, db.queryLong("SELECT count(*) FROM t"))
            assertEquals("alice", db.queryText("SELECT name FROM t ORDER BY id LIMIT 1"))
            assertEquals("wal", db.queryText("PRAGMA journal_mode"))
            assertEquals("ok", db.queryText("PRAGMA integrity_check"))
        }
    }

    @Test
    fun `가상 스레드 동시 writer+reader — 유실 없음 + integrity`() {
        val dir = Files.createTempDirectory("sqlitewal")
        SqliteWal.open(dir, "vt.db", readers = 3).use { db ->
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, tag INTEGER)")
            val writers = 6; val perWriter = 50
            val readerThreads = 8; val readsEach = 100
            val maxSeen = AtomicLong()

            Executors.newVirtualThreadPerTaskExecutor().use { ex ->
                val fs = ArrayList<Future<*>>()
                repeat(writers) {
                    fs += ex.submit {
                        repeat(perWriter) { db.exec("INSERT INTO t(tag) VALUES (1)") }
                    }
                }
                repeat(readerThreads) {
                    fs += ex.submit {
                        repeat(readsEach) {
                            val c = db.queryLong("SELECT count(*) FROM t")
                            maxSeen.accumulateAndGet(c) { a, b -> maxOf(a, b) }
                        }
                    }
                }
                fs.forEach { it.get() }   // 예외 전파
            }

            val expected = (writers * perWriter).toLong()
            assertEquals(expected, db.queryLong("SELECT count(*) FROM t"))
            assertEquals("ok", db.queryText("PRAGMA integrity_check"))
            assertTrue(maxSeen.get() in 1..expected) { "maxSeen=${maxSeen.get()}" }
        }
    }

    @Test
    fun `부하 - reader-during-write 진짜 겹침 (6w×200 + 8r×400)`() {
        val dir = Files.createTempDirectory("sqlitewal")
        SqliteWal.open(dir, "load.db", readers = 4).use { db ->
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, tag INTEGER)")
            val writers = 6; val perWriter = 200
            val readerThreads = 8; val readsEach = 400
            val maxSeen = AtomicLong()

            Executors.newVirtualThreadPerTaskExecutor().use { ex ->
                val fs = ArrayList<Future<*>>()
                repeat(writers) {
                    fs += ex.submit {
                        repeat(perWriter) { db.exec("INSERT INTO t(tag) VALUES (1)") }
                    }
                }
                repeat(readerThreads) {
                    fs += ex.submit {
                        repeat(readsEach) {
                            val c = db.queryLong("SELECT count(*) FROM t")
                            maxSeen.accumulateAndGet(c) { a, b -> maxOf(a, b) }
                        }
                    }
                }
                fs.forEach { it.get() }
            }

            val expected = (writers * perWriter).toLong()
            assertEquals(expected, db.queryLong("SELECT count(*) FROM t"))
            assertEquals("ok", db.queryText("PRAGMA integrity_check"))
            assertTrue(maxSeen.get() in 1..expected) { "maxSeen=${maxSeen.get()}" }
        }
    }

    @Test
    fun `IO-헤비 - 캐시보다 큰 DB 랜덤 포인트 읽기 (fd 동시성 측정용)`() {
        val dir = Files.createTempDirectory("sqlitewal")
        SqliteWal.open(dir, "io.db", readers = 4).use { db ->
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)")
            // ~100k × 96B ≈ 12MB+ > 커넥션당 기본 페이지 캐시(2MB) → 랜덤 읽기 대부분 캐시 미스 = fd_pread
            db.exec(
                "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<100000) " +
                "INSERT INTO t(id,v) SELECT x, hex(randomblob(48)) FROM c"
            )
            assertEquals(100_000L, db.queryLong("SELECT count(*) FROM t"))

            val readerThreads = 8; val readsEach = 250
            Executors.newVirtualThreadPerTaskExecutor().use { ex ->
                val fs = ArrayList<Future<*>>()
                repeat(readerThreads) {
                    fs += ex.submit {
                        repeat(readsEach) {
                            // 같은 SQL 문자열(stmt 캐시 적중) — 랜덤성은 엔진 안에서. 순수하게 I/O 동시성만 잰다.
                            val len = db.queryLong("SELECT length(v) FROM t WHERE id=(abs(random())%100000)+1")
                            check(len == 96L) { "len=$len" }
                        }
                    }
                }
                fs.forEach { it.get() }
            }
            assertEquals("ok", db.queryText("PRAGMA integrity_check"))
        }
    }

    @Test
    fun `sorter 보조 스레드 (PRAGMA threads) — 하이브리드 spawn 위임`() {
        val dir = Files.createTempDirectory("sqlitewal")
        SqliteWal.open(dir, "sort.db", readers = 1).use { db ->
            db.exec("CREATE TABLE t(v INTEGER)")
            db.exec(
                "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<200000) " +
                "INSERT INTO t(v) SELECT (x*2654435761)%1000000 FROM c"
            )
            db.exec("PRAGMA threads=4")
            db.exec("PRAGMA cache_size=-200")    // 정렬 버퍼 축소 → PMA 스필 → 보조 스레드 유도
            db.exec("CREATE INDEX idx_v ON t(v)")
            assertEquals(200_000L, db.queryLong("SELECT count(*) FROM t"))
            assertEquals("ok", db.queryText("PRAGMA integrity_check"))
            // child(writer 워커) 내부 pthread_create 가 하이브리드 디스패처를 거쳐 진짜 spawn 으로 위임됐는지
            assertTrue(db.rt.delegatedSpawns.get() >= 1) {
                "sorter 보조 스레드 위임 0회 — threads pragma 가 무시됐거나 스필 미발생"
            }
        }
    }

    @Test
    fun `재오픈 영속성`() {
        val dir = Files.createTempDirectory("sqlitewal")
        SqliteWal.open(dir, "persist.db", readers = 1).use { db ->
            db.exec("CREATE TABLE t(v INTEGER)")
            db.exec("INSERT INTO t(v) VALUES (42)")
        }
        SqliteWal.open(dir, "persist.db", readers = 1).use { db ->
            assertEquals(42L, db.queryLong("SELECT v FROM t"))
        }
    }
}
