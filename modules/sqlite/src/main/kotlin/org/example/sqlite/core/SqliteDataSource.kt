package org.example.sqlite.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JDBC 형 온디맨드 커넥션 — `javax.sql.DataSource`/`java.sql.Connection` 의 형태에 맞춘 표면.
 *
 * ```kotlin
 * SqliteDataSource(dir, "app.db").use { ds ->
 *     ds.getConnection().use { con ->        // ← 이 순간 워커(wasm pthread + WAL 커넥션) 생성
 *         con.execute("INSERT ...")
 *         con.queryLong("SELECT count(*) FROM t")
 *     }                                      // ← close = 워커 STOP + join
 * }
 * ```
 *
 * JDBC 대응(향후 어댑터 매핑): `SqliteDataSource`→DataSource, [getConnection]→DataSource.getConnection,
 * [SqliteConnection.execute]→Statement.execute, query*→스칼라 ResultSet.
 * (다음 단계: 다중 행/열 ResultSet, 바인딩 = PreparedStatement)
 *
 * 동시성 모델: 커넥션마다 독립 WAL 커넥션이므로 **여러 커넥션이 동시에 읽고 쓸 수 있다** —
 * writer 간 직렬화는 WAL 자체의 write 락([JvmVfsLocks] = 진짜 JVM 락) + busy 재시도가 담당한다.
 * [getConnection] 의 워커 spawn 은 내부 락으로 직렬화된다 (§9 규율 — 사용자에게 노출 안 됨).
 */
@OptIn(InternalRuntimeApi::class)
class SqliteDataSource(hostDir: Path, fileName: String) : AutoCloseable {

    internal val rt: JvmVfsRuntime
    private val guestPath: String
    private val ownerLock: DbOwnerLock
    private val spawnLock = ReentrantLock()
    private val connections = ConcurrentHashMap.newKeySet<SqliteConnection>()
    @Volatile private var closed = false

    init {
        when (val h = openRuntime(hostDir, fileName)) {
            is RuntimeHandle -> {
                rt = h.rt
                guestPath = h.guestPath
                ownerLock = h.ownerLock!!
            }
        }
    }

    /** 커넥션 생성 — 워커를 그때그때 spawn (open+WAL+warmup 완료 후 반환). */
    fun getConnection(): SqliteConnection {
        check(!closed) { "DataSource closed" }
        spawnLock.withLock {                       // §9: 동시 spawn 금지 — 내부에서 직렬화
            val worker = SqliteWorker.spawn(rt, guestPath)
            val con = SqliteConnection(this, worker)
            connections += con
            return con
        }
    }

    internal fun onConnectionClosed(c: SqliteConnection) {
        connections -= c
    }

    /** 열려 있는 커넥션을 전부 닫고 런타임을 내린 뒤 단독 소유 락을 해제한다. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            connections.toList().forEach { it.close() }
            rt.close()
        } finally {
            ownerLock.close()   // 정리 실패 시에도 소유권은 반납 (잔류 락 방지)
        }
    }

    /**
     * 불투명 런타임 핸들 — 내부 런타임/락 구조는 감추고, 포트 spawn 과 close 만 노출한다.
     * core 밖에서 구현 불가(sealed)이고, 획득은 [openRuntime]/[openMemoryRuntime](opt-in)로만.
     */
    sealed interface IRuntimeHandle {
        /** 이 런타임 위에 워커 포트 1개 spawn — 같은 핸들의 포트들은 런타임을 공유한다. */
        fun spawnPort(readOnly: Boolean, release: Runnable): WorkerDbPort

        /** 런타임 종료 + 단독 소유 락 반납 + (`:memory:`) 임시 디렉터리 정리. */
        fun close()
    }

    internal class RuntimeHandle(
        internal val rt: JvmVfsRuntime,
        internal val guestPath: String,
        private val hostTmp: Path,
        internal val ownerLock: DbOwnerLock?,   // null = :memory:
        private val deleteTmpOnClose: Boolean,
    ) : IRuntimeHandle {
        private val spawnLock = ReentrantLock()

        override fun spawnPort(readOnly: Boolean, release: Runnable): WorkerDbPort =
            WorkerDbPort.spawn(rt, guestPath, spawnLock, readOnly, hostTmp, release)

        override fun close() {
            try {
                rt.close()
            } finally {
                ownerLock?.close()
                if (deleteTmpOnClose) runCatching {
                    Files.list(hostTmp).use { l -> l.forEach { Files.deleteIfExists(it) } }
                    Files.deleteIfExists(hostTmp)
                }
            }
        }
    }

    companion object {
        /**
         * 파일 런타임 + 준비 공통 경로 ([SqliteWal] 과 공유):
         * **단독 소유 락([DbOwnerLock]) 선획득** → "/db"(데이터) + "/tmp"(sorter PMA 스필·VACUUM·
         * 임시 테이블) preopen → 파일 선-생성 + WAL 영속화 (동시 생성/wal-index 초기화 경합 방지).
         */
        @InternalRuntimeApi
        fun openRuntime(hostDir: Path, fileName: String): IRuntimeHandle {
            val ownerLock = DbOwnerLock.acquire(hostDir, fileName)
            try {
                val tmp = hostDir.resolve(".tmp").also { Files.createDirectories(it) }
                val rt = JvmVfsRuntime(mapOf("/db" to hostDir, "/tmp" to tmp))
                val guestPath = "/db/$fileName"
                val init = rt.openDb(guestPath)
                rt.exec(init, "PRAGMA journal_mode=WAL")
                rt.closeDb(init)
                return RuntimeHandle(rt, guestPath, tmp, ownerLock, deleteTmpOnClose = false)
            } catch (t: Throwable) {
                ownerLock.close()
                throw t
            }
        }

        /** `:memory:` 런타임 — 워커별 사유 인메모리 DB. backup/restore 중계용 "/tmp" preopen. */
        @InternalRuntimeApi
        fun openMemoryRuntime(): IRuntimeHandle {
            val tmp = Files.createTempDirectory("sqlite-jvm-mem")
            try {
                val rt = JvmVfsRuntime(mapOf("/tmp" to tmp))
                return RuntimeHandle(rt, ":memory:", tmp, ownerLock = null, deleteTmpOnClose = true)
            } catch (t: Throwable) {
                runCatching { Files.deleteIfExists(tmp) }
                throw t
            }
        }
    }
}

/**
 * 워커 1개(= 독립 WAL 커넥션)를 소유하는 JDBC 형 커넥션. 스레드 안전 — 여러 caller 가 제출하면
 * 워커 큐에서 순서대로 실행된다 (JDBC Connection 과 같은 "한 커넥션 = 한 실행 스트림" 시맨틱).
 */
class SqliteConnection internal constructor(
    private val ds: SqliteDataSource,
    private val worker: SqliteWorker,
) : AutoCloseable {

    @Volatile private var closed = false
    private val statements = ConcurrentHashMap.newKeySet<SqlitePreparedStatement>()

    /**
     * 실행 중인 문장을 중단한다 (JDBC `Statement.cancel()` 대응) — 워커의 sqlite3* 에
     * `sqlite3_interrupt` 플래그를 세워, 진행 중 문장이 rc=9(SQLITE_INTERRUPT)로 즉시 풀린다.
     * 실행 중 문장이 없으면 no-op. 커넥션은 취소 후에도 계속 사용 가능.
     */
    fun cancel() {
        ds.rt.interruptDb(worker.dbPtr)
    }

    /** ?-바인딩 prepared statement 생성 — stmt 는
     *  이 커넥션의 워커 소유 (LRU 캐시 밖, [SqlitePreparedStatement.close] 까지). */
    fun prepare(sql: String): SqlitePreparedStatement {
        val (st, n) = submitTask("prepare:$sql") { s -> s.prepareUser(sql) }
        val ps = SqlitePreparedStatement(this, st, n, sql)
        statements += ps
        return ps
    }

    /** DDL/DML 실행. [params] = ?-바인딩 값 (1-based 순서). rc!=0 이면 예외 (errmsg 포함). */
    fun execute(sql: String, vararg params: Any?) {
        val r = submit(sql, isQuery = false, params.toList())
        check(r.rc == 0) { "execute rc=${r.rc} (${r.text}): $sql" }
    }

    /** SELECT 첫 행 첫 컬럼을 정수로. [params] = ?-바인딩 값. */
    fun queryLong(sql: String, vararg params: Any?): Long {
        val r = submit(sql, isQuery = true, params.toList())
        check(r.rc == 0) { "query rc=${r.rc} (${r.text}): $sql" }
        return r.long
    }

    /** SELECT 첫 행 첫 컬럼을 텍스트로. 빈 결과 "". [params] = ?-바인딩 값. */
    fun queryText(sql: String, vararg params: Any?): String {
        val r = submit(sql, isQuery = true, params.toList())
        check(r.rc == 0) { "query rc=${r.rc} (${r.text}): $sql" }
        return r.text
    }

    /**
     * 다중 행/열 SELECT — 전 행을 **한 워커 태스크로** materialize (행당 왕복 없음, 스냅샷 원자적).
     * 결과 전체가 JVM 힙에 올라오므로 대형 결과는 LIMIT 로 제한할 것.
     */
    fun query(sql: String, vararg params: Any?): List<SqliteRow> {
        val p = params.toList()
        return submitTask(sql) { s -> s.queryRows(sql, p) }
    }

    private fun submit(sql: String, isQuery: Boolean, params: List<Any?>): SqlResult {
        check(!closed) { "Connection closed" }
        return worker.run(sql, isQuery, params)
    }

    /** [SqlitePreparedStatement] 용 클로저 실행 — caller 스레드에서 직접 (per-child 락 직렬화). */
    internal fun <T> submitTask(label: String, fn: (SqliteWorker.Session) -> T): T {
        check(!closed) { "Connection closed" }
        return worker.run(fn)
    }

    internal fun onStatementClosed(ps: SqlitePreparedStatement) {
        statements -= ps
    }

    /** 워커 종료 — in-flight 호출은 per-child 락으로 자연 대기. 멱등. */
    override fun close() {
        if (closed) return
        closed = true
        // 열린 stmt 는 닫힘 표시만 — 실제 finalize 는 워커 종료(Session.close)가 일괄 수행
        statements.forEach { it.markClosed() }
        statements.clear()
        worker.stop()
        ds.onConnectionClosed(this)
    }
}
