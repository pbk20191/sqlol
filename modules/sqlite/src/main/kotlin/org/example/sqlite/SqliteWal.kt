package org.example.sqlite

import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 다중 커넥션 WAL — **고정 풀형** single-writer + bounded-reader. (온디맨드 커넥션은 [SqliteDataSource])
 *
 * park-carrier 직접 실행 모델 (§9.2): 워커는 잠든 wasm pthread + WAL 커넥션이고, 호출은 caller
 * 스레드가 직접 한다:
 *  - **writer 워커 1**: per-child 락이 곧 writer 직렬화 (WAL 의 "동시 writer 1명").
 *  - **reader 워커 N + 풀(LinkedBlockingQueue)**: caller 가 유휴 reader 를 빌려 직접 실행 후 반납 —
 *    워크스틸링 동치. **read 는 write 와 겹쳐도 안전** (WAL 스냅샷 읽기, §9.1).
 *  - 견고화 규율: 파일 선-생성 / 순차 spawn (§9) / BUSY·PROTOCOL 재시도 (Session 코어).
 *
 * 전제: JEP 491(JDK 24+) JVM — caller 가 가상 스레드여도 락/park 에서 pinning 없음.
 */
class SqliteWal private constructor(
    internal val rt: JvmVfsRuntime,
    private val ownerLock: DbOwnerLock,
    private val writer: SqliteWorker,
    private val readers: List<SqliteWorker>,
    private val readerPool: LinkedBlockingQueue<SqliteWorker>,
) : AutoCloseable {

    private fun <T> borrowReader(fn: (SqliteWorker) -> T): T {
        val w = readerPool.poll(60, TimeUnit.SECONDS) ?: error("reader 풀 고갈 (60s)")
        try {
            return fn(w)
        } finally {
            readerPool.put(w)
        }
    }

    /** DDL/DML — writer 워커의 per-child 락이 직렬화 (구조 그대로). [params] = ?-바인딩 값. rc!=0 이면 예외. */
    fun exec(sql: String, vararg params: Any?) {
        val r = writer.run(sql, isQuery = false, params.toList())
        check(r.rc == 0) { "exec rc=${r.rc} (${r.text}): $sql" }
    }

    /** SELECT 첫 행 첫 컬럼을 정수로 — write 와도 동시 (WAL 스냅샷 읽기). [params] = ?-바인딩 값. */
    fun queryLong(sql: String, vararg params: Any?): Long = borrowReader { w ->
        val r = w.run(sql, isQuery = true, params.toList())
        check(r.rc == 0) { "query rc=${r.rc} (${r.text}): $sql" }
        r.long
    }

    /** SELECT 첫 행 첫 컬럼을 텍스트로 — write 와도 동시. 빈 결과 "". [params] = ?-바인딩 값. */
    fun queryText(sql: String, vararg params: Any?): String = borrowReader { w ->
        val r = w.run(sql, isQuery = true, params.toList())
        check(r.rc == 0) { "query rc=${r.rc} (${r.text}): $sql" }
        r.text
    }

    /**
     * 다중 행/열 SELECT — reader 를 빌려 caller 스레드에서 전 행 materialize (행당 왕복 없음,
     * 한 워커가 끝까지 처리하므로 스냅샷 안전). 대형 결과는 LIMIT 로 제한할 것.
     */
    fun query(sql: String, vararg params: Any?): List<SqliteRow> {
        val p = params.toList()
        return borrowReader { w -> w.run { s -> s.queryRows(sql, p) } }
    }

    override fun close() {
        try {
            // 풀에서 전부 회수(= in-flight 반납 대기) 후 종료 — STOP 센티널 불필요
            writer.stop()
            readers.forEach { it.stop() }
            rt.close()
        } finally {
            ownerLock.close()   // 정리 실패 시에도 소유권은 반납 (잔류 락 방지)
        }
    }

    companion object {
        /**
         * [hostDir]/[fileName] 파일 DB 를 writer 1 + reader [readers] 워커로 연다.
         * 스키마(CREATE TABLE 등)는 생성 후 [exec] 로 만든다.
         */
        fun open(hostDir: Path, fileName: String, readers: Int = 3): SqliteWal {
            val h = SqliteDataSource.openRuntime(hostDir, fileName)
            // 순차 spawn (§9: 동시 open/WAL/memory.grow 는 공유메모리 경합 → 하나씩)
            val writer = SqliteWorker.spawn(h.rt, h.guestPath)
            val readerWorkers = (1..readers).map { SqliteWorker.spawn(h.rt, h.guestPath) }
            val pool = LinkedBlockingQueue<SqliteWorker>(readerWorkers)
            return SqliteWal(h.rt, h.ownerLock, writer, readerWorkers, pool)
        }
    }
}
