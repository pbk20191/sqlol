package org.example.sqlite

import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 다중 커넥션 WAL 커넥션 풀.
 *
 * 각 워커는 wasm 안의 pthread 스레드로, 같은 파일을 WAL 로 연 sqlite3 커넥션 하나를 소유한다.
 * JVM 은 풀에서 워커를 빌려 SQL 을 제출하고(공유메모리 채널), 여러 스레드가 서로 다른 워커를 통해
 * **동시에** 접근한다 (다중 reader + 단일 writer, WAL 조율은 [JvmVfsLocks]).
 *
 * 채널(bchannel_t): req@0(i32) done@4(i32) rc@8 result@16(i64) sql@24(cstr) dbpath@4096.
 * result = SELECT 첫 컬럼 정수값. done = 워커 완료 시퀀스(+1).
 */
class SqliteWalPool private constructor(
    private val rt: JvmVfsRuntime,
    private val idle: ArrayBlockingQueue<Int>,
    private val channels: List<Int>,
) : AutoCloseable {

    /**
     * writer 직렬화 락. WAL 은 동시 writer 1명만 허용하며, 다중 writer 가 wal-index 를 동시 갱신하면
     * 공유메모리 가시성 경합으로 커밋 유실이 발생(Chicory 배리어 한계). JVM 레벨에서 writer 를 직렬화하고
     * reader 는 동시 허용한다 (WAL: reader 가 writer 를 막지 않음).
     */
    private val rwl = ReentrantReadWriteLock()

    /**
     * 채널에 SQL 을 1회 제출하고 결과 대기 — **무폴링 블로킹**. (rc, result) 반환.
     *
     * 방향별 주소 분리(req/done)로 wakeup 도둑질을 제거: 워커는 `req` 에서만, JVM 은 `done` 에서만 대기한다.
     * JVM 은 `atomicWriteInt(req)+atomicNotify(req)` 로 깨우고 `atomicWait(done, ...)` 로 완료를 블로킹 수신.
     * 30s 타임아웃은 데드락 watchdog 일 뿐 핫패스 폴링이 아니다(스레드는 notify 까지 park). 상세는 BUILD.md §8.
     *
     * 메모리 접근만으로 동작하므로(메인 인스턴스 export 호출 없음) 채널별로 락 없이 동시 진행한다.
     */
    private fun submitOnce(ch: Int, sql: String): Pair<Int, Long> {
        val b = sql.toByteArray(Charsets.UTF_8)
        require(b.size < 4072) { "SQL too long" }
        rt.mem.write(ch + SQL, b)
        rt.mem.write(ch + SQL + b.size, byteArrayOf(0))
        val prevDone = rt.mem.atomicReadInt(ch + DONE)
        rt.mem.atomicWriteInt(ch + REQ, 1)                        // 요청 게시
        rt.mem.atomicNotify(ch + REQ, 1)                          // 워커 깨우기
        val deadline = System.nanoTime() + 30_000_000_000L
        while (rt.mem.atomicReadInt(ch + DONE) == prevDone) {     // done 증가까지 블로킹
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) error("워커 응답 타임아웃 (sql=$sql)")
            rt.mem.atomicWait(ch + DONE, prevDone, remaining)
        }
        return rt.mem.readInt(ch + RC) to rt.mem.readLong(ch + RESULT)
    }

    /** BUSY(5) 는 일시적(특히 startup wal-index 초기화 경합) → 재시도 */
    private fun submit(ch: Int, sql: String): Pair<Int, Long> {
        repeat(100) {
            val r = submitOnce(ch, sql)
            if (r.first != 5) return r
            Thread.sleep(10)
        }
        return submitOnce(ch, sql)
    }

    private fun <T> borrow(block: (Int) -> T): T {
        val ch = idle.take()
        try {
            return block(ch)
        } finally {
            idle.put(ch)
        }
    }

    /** DDL/DML 실행 (writer: 직렬화). rc!=0 이면 예외 */
    fun exec(sql: String) {
        rwl.writeLock().lock()
        try {
            borrow { ch ->
                val (rc, _) = submit(ch, sql)
                check(rc == 0) { "exec rc=$rc: $sql" }
            }
        } finally {
            rwl.writeLock().unlock()
        }
    }

    /**
     * SELECT 의 첫 행 첫 컬럼을 정수로 (reader: 동시 허용).
     *
     * 동시 reader 안전성은 **공유 WASI 의 thread-safe 화**(JvmVfsRuntime: 단일 WASI + `synchronized(wasi)` 래핑)로
     * 확보. 과거 워커별 독립 WASI 에선 동시 reader 부하에서 transient rc=26/11 발생했는데, 근본 원인은 wal-index
     * 코히런스가 아니라 **WASI fd table(`Descriptors`: ArrayList/TreeSet, 비동기)의 thread-safety 부재**였다.
     * 검증: SqliteWalPoolLoadTest(8r×400 18/18). 상세: BUILD.md §7.
     */
    fun queryLong(sql: String): Long {
        rwl.readLock().lock()
        try {
            return borrow { ch ->
                val (rc, r) = submit(ch, sql)
                check(rc == 0) { "query rc=$rc: $sql" }
                r
            }
        } finally {
            rwl.readLock().unlock()
        }
    }

    /** SELECT 의 첫 행 첫 컬럼을 텍스트로 (reader: 동시 허용). 빈 결과 시 "". 2047바이트 초과 시 절단. */
    fun queryText(sql: String): String {
        rwl.readLock().lock()
        try {
            return borrow { ch ->
                val (rc, _) = submit(ch, sql)
                check(rc == 0) { "query rc=$rc: $sql" }
                rt.mem.readCString(ch + RESTEXT)
            }
        } finally {
            rwl.readLock().unlock()
        }
    }

    override fun close() {
        channels.forEach { ch ->                       // stop: req=3 + 깨우기 (메모리 접근만)
            rt.mem.atomicWriteInt(ch + REQ, 3)
            rt.mem.atomicNotify(ch + REQ, 1)
        }
        rt.close()
    }

    companion object {
        // bchannel_t 오프셋
        private const val REQ = 0
        private const val DONE = 4
        private const val RC = 8
        private const val RESULT = 16
        private const val SQL = 24
        private const val RESTEXT = 4608

        /**
         * [hostDir] 아래 [fileName] 파일 DB 에 대해 [size] 개의 WAL 워커 커넥션 풀을 연다.
         * 스키마(CREATE TABLE 등)는 풀 생성 후 `exec` 로 먼저 만든다.
         */
        fun open(hostDir: Path, fileName: String, size: Int = 4): SqliteWalPool {
            val rt = JvmVfsRuntime.open(preopens = mapOf("/db" to hostDir))
            val guestPath = "/db/$fileName"
            // 워커 spawn 전에 파일을 선-생성 + WAL 영속화 (동시 생성/wal-index 초기화 경합 방지)
            val init = rt.openDb(guestPath)
            rt.exec(init, "PRAGMA journal_mode=WAL")
            rt.closeDb(init)

            val channels = ArrayList<Int>(size)
            val pool = SqliteWalPool(rt, ArrayBlockingQueue(size), channels)
            // 워커를 순차적으로 spawn + warmup(첫 쿼리로 open 완료 보장) → 동시 파일/-wal 생성 경합 방지
            repeat(size) {
                val ch = rt.malloc(8192)
                rt.mem.fill(0, ch, ch + 8192)
                val pathPtr = rt.cString(guestPath)
                check(rt.exports.startWorkerFile2addr(ch, pathPtr) == 0) { "start_worker_file_2addr 실패" }
                val (rc, _) = pool.submit(ch, "SELECT 1")   // open 완료까지 대기 (BUSY 재시도 포함)
                check(rc == 0) { "worker warmup rc=$rc" }
                channels += ch
                pool.idle.put(ch)
            }
            return pool
        }
    }
}
