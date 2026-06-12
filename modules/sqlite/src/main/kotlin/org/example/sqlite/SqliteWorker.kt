package org.example.sqlite

import com.dylibso.chicory.runtime.Memory
import com.example.wasm.JvmVfsModule_ModuleExports
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** rc + 첫 행 첫 컬럼 (정수/텍스트). 행 없으면 0L/"". rc!=0 이면 text 에 errmsg. */
internal class SqlResult(val rc: Int, val long: Long, val text: String)

/**
 * SQLite 워커 — **park-carrier 직접 실행 모델** (§9.2). [SqliteWal]/[SqliteConnection]/[WorkerDbPort] 공유 코어.
 *
 * 워커 = 잠든 wasm pthread([JvmVfsRuntime.ChildModule] — 정체성 캐리어) + 그 child 의 WAL 커넥션
 * 1개([Session]). **호출은 caller 스레드가 [run] 으로 직접** 한다 — 큐/future 마샬링 없음.
 * child 의 __stack_pointer 가 공유 자원이므로 per-child 락이 호출을 직렬화한다
 * (JDBC 의 "한 커넥션 = 한 실행 스트림" 시맨틱과 일치). 콜백(UDF 등)은 그 caller 스레드에서
 * 재진입으로 돈다 — sqlite4j 와 동일 모니터 시맨틱.
 */
internal class SqliteWorker private constructor(
    private val rt: JvmVfsRuntime,
    private val child: JvmVfsRuntime.ChildModule,
    private val session: Session,
) {
    /** 워커 소유 sqlite3* — [JvmVfsRuntime.interruptDb] 의 대상 (락 없이 접근 가능 — 실행 중 취소용). */
    val dbPtr: Int get() = session.db

    private val lock = ReentrantLock()
    @Volatile private var closed = false

    /** [fn] 을 이 워커의 [Session] 에 대해 **이 스레드에서** 실행 (per-child 직렬화). */
    fun <T> run(fn: (Session) -> T): T = lock.withLock {
        check(!closed) { "worker closed" }
        fn(session)
    }

    /** SQL 1건 편의 표면 — [Session.run] 직접 호출. [params] = ?-바인딩 값 (1-based 순서). */
    fun run(sql: String, isQuery: Boolean, params: List<Any?> = emptyList()): SqlResult =
        run { s -> s.run(sql, isQuery, params) }

    /**
     * 종료: 커넥션 close(guest) → park 해제 → VT join (= libc detached exit 완료). 멱등.
     * in-flight 호출은 락으로 자연 대기 — 무한 문장은 [JvmVfsRuntime.interruptDb] 로 먼저 중단할 것.
     */
    fun stop() {
        check(lock.tryLock(60, TimeUnit.SECONDS)) {
            "워커 종료 대기 초과 — 실행 중 문장은 cancel(interruptDb)로 중단 가능; workerErrors=${rt.workerErrors}"
        }
        try {
            if (closed) return
            closed = true
            session.close()
            rt.closeChild(child)
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private const val SQL_BUF = 4096
        private const val STMT_CACHE = 64

        /** sqlite 특수 소멸자 ((void*)-1) — bind 호출 중에 값을 복사하라는 지시. 스크래치 즉시 재사용 가능. */
        private const val SQLITE_TRANSIENT = -1

        /** SQLITE_OPEN_READWRITE(2) or SQLITE_OPEN_CREATE(4) — sqlite3_open 과 동등한 기본. */
        const val OPEN_DEFAULT = 6

        /** SQLITE_OPEN_READONLY. */
        const val OPEN_READONLY = 1

        /**
         * 워커 1개 spawn + open/WAL/warmup — **전부 caller 스레드에서 동기로** (래치/워치독 불필요,
         * 실패는 원인 예외 그대로 — rc 보존 [SqliteNativeException] 포함).
         * 호출측은 **spawn 직렬화**를 보장할 것 (§9 — 동시 open/WAL/grow 초기화 경합 금지).
         */
        fun spawn(rt: JvmVfsRuntime, guestPath: String, openFlags: Int = OPEN_DEFAULT): SqliteWorker {
            val child = rt.spawnPthread()
            try {
                val s = Session(child.exports, rt.mem, guestPath, openFlags)
                try {
                    s.exec("PRAGMA journal_mode=WAL")   // readonly/:memory: 면 거부돼도 무해 (rc 무시)
                    // WAL 공식 권장 조합: NORMAL 은 커밋마다 fsync 하지 않고 checkpoint 에서만 sync.
                    s.exec("PRAGMA synchronous=NORMAL")
                    s.exec("PRAGMA busy_timeout=5000")
                    check(s.run("SELECT 1", isQuery = true).long == 1L) { "warmup 실패" }
                } catch (t: Throwable) {
                    s.close()
                    throw t
                }
                return SqliteWorker(rt, child, s)
            } catch (t: Throwable) {
                runCatching { rt.closeChild(child) }
                throw t
            }
        }
    }

    /**
     * 워커가 소유한 커넥션 1개에 대한 간이 C API — [run] 클로저가 받는 실행 컨텍스트.
     * **per-child 락 하 단일 스레드** (동시 접근 없음). 생성 = open (실패 시 예외).
     *
     * 클로저용 표면: 고수준 [run]/[exec]/[query] + 저수준 [x]/[mem]/[db]/[sqlPtr]
     * (향후 바인딩/ResultSet 이 prepare→bind→step→column 을 여기서 조립).
     *
     * 공유메모리 접근은 [StatelessBulkMemory] 전제로 벌크 사용 가능 (§9.1 함정 3 — position 레이스는
     * Memory 구현에서 근원 해소). SQL/포인터 슬롯은 생성 시 1회 할당하는 고정 버퍼 재사용 —
     * 부하 중 JVM 발 malloc 0.
     */
    internal class Session(
        val x: JvmVfsModule_ModuleExports,
        val mem: Memory,
        guestPath: String,
        openFlags: Int = OPEN_DEFAULT,
    ) {
        private val pp = x.malloc(4)        // stmt/db 포인터 out-슬롯
        private var buf = x.malloc(SQL_BUF) // sql cstr — 부족 시 2배 성장 (DBMetaData 류 5KB+ SQL)
        private var bufCap = SQL_BUF

        /** 워커 소유 sqlite3* — 클로저가 raw export 호출 시 db 핸들로 사용. */
        val db: Int

        init {
            val rc = x.sqlite3OpenV2(sqlPtr(guestPath), pp, openFlags, 0)
            db = mem.readInt(pp)
            if (rc != 0) throw SqliteNativeException(rc, "sqlite3_open($guestPath) rc=$rc")
        }

        /** [s] 를 SQL 버퍼에 NUL-종단 cstr 로 써넣고 포인터 반환 (다음 호출 시 덮어씀). */
        fun sqlPtr(s: String): Int {
            val b = s.toByteArray(Charsets.UTF_8)
            if (b.size >= bufCap) {
                x.free(buf)
                bufCap = maxOf(b.size + 1, bufCap * 2)
                buf = x.malloc(bufCap)
            }
            mem.write(buf, b)
            mem.writeByte(buf + b.size, 0)
            return buf
        }

        /**
         * prepared statement LRU 캐시 (sql → stmt). 워커 전용이라 동시성 없음. 반복 SQL 의 파싱/플래닝 제거.
         * 바인딩 API 가 없어 사용자가 값을 보간하면 SQL 이 전부 고유해질 수 있으므로 상한 필수
         * (무제한이면 stmt 가 wasm 메모리에 무한 누적). 초과 시 가장 오래 안 쓴 stmt 를 finalize.
         */
        private val stmts = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean {
                if (size > STMT_CACHE) { x.sqlite3Finalize(eldest.value); return true }
                return false
            }
        }

        /**
         * 사용자 소유 stmt registry (LRU 캐시 밖 — [SqlitePreparedStatement] 의 백엔드).
         * 수명: [prepareUser] ~ [finalizeUser], 잔존분은 [close] 가 정리 (커넥션이 먼저 닫혀도 누수 없음).
         * 동시에 use-after-finalize 가드: [runUser] 는 등록 여부를 먼저 확인한다 (dangling ptr step 방지).
         */
        private val userStmts = LinkedHashSet<Int>()

        /** 텍스트/블롭 바인딩용 스크래치 (워커 전용, 2배 성장). TRANSIENT 바인드라 호출 직후 재사용 가능. */
        private var bindBuf = 0
        private var bindCap = 0

        private fun scratch(n: Int): Int {
            if (bindBuf == 0 || n > bindCap) {
                if (bindBuf != 0) x.free(bindBuf)
                bindCap = maxOf(n, bindCap * 2, SQL_BUF)
                bindBuf = x.malloc(bindCap)
            }
            return bindBuf
        }

        fun close() {
            userStmts.forEach { x.sqlite3Finalize(it) }
            userStmts.clear()
            stmts.values.forEach { x.sqlite3Finalize(it) }
            stmts.clear()
            if (bindBuf != 0) { x.free(bindBuf); bindBuf = 0 }
            x.free(buf)
            x.free(pp)
            x.sqlite3CloseV2(db)
        }

        fun exec(sql: String): Int = x.sqlite3Exec(db, sqlPtr(sql), 0, 0, 0)

        /** ?-파라미터 1개 바인딩 (1-based) — rc 반환. [WorkerDbPort] 의 개별 bind_* 절단면도 이걸 쓴다. */
        fun bindOne(st: Int, i: Int, v: Any?): Int = when (v) {
            null -> x.sqlite3BindNull(st, i)
            is Long -> x.sqlite3BindInt64(st, i, v)
            is Int -> x.sqlite3BindInt64(st, i, v.toLong())
            is Boolean -> x.sqlite3BindInt64(st, i, if (v) 1L else 0L)
            is Double -> x.sqlite3BindDouble(st, i, v)
            is Float -> x.sqlite3BindDouble(st, i, v.toDouble())
            is String -> {
                val b = v.toByteArray(Charsets.UTF_8)
                val p = scratch(b.size)
                mem.write(p, b)
                x.sqlite3BindText(st, i, p, b.size, SQLITE_TRANSIENT)
            }
            is ByteArray -> {
                val p = scratch(v.size)
                mem.write(p, v)
                x.sqlite3BindBlob(st, i, p, v.size, SQLITE_TRANSIENT)
            }
            else -> throw IllegalArgumentException("바인딩 불가 타입: ${v?.let { it::class.simpleName }} (인덱스 $i)")
        }

        /** ?-파라미터 전체 바인딩 (1-based). 빈 리스트면 no-op. */
        fun bindAll(st: Int, params: List<Any?>) {
            params.forEachIndexed { i0, v ->
                val rc = bindOne(st, i0 + 1, v)
                check(rc == 0) { "bind[${i0 + 1}] rc=$rc (${errmsg(rc)})" }
            }
        }

        /**
         * 캐시된 stmt 로 1회 실행 — rc + 첫 행 첫 컬럼 캡처 (행 없으면 0L/"").
         * prepare 는 SQL 당 1회, 이후 reset+step 재사용. 스키마 변경은 prepare_v2 가 내부 재컴파일로 흡수.
         * [params] 바인딩 시 SQL 텍스트가 상수가 되므로 캐시 적중 100% — 실행 후 clear_bindings 로
         * 같은 SQL 의 비바인딩 재사용에 값이 새지 않게 한다.
         */
        fun query(sql: String, params: List<Any?> = emptyList()): SqlResult {
            val st = stmts[sql] ?: run {
                val prc = x.sqlite3PrepareV2(db, sqlPtr(sql), -1, pp, 0)
                val st = mem.readInt(pp)
                if (prc != 0 || st == 0) return SqlResult(prc, 0L, errmsg(prc))
                stmts[sql] = st
                st
            }
            return try {
                bindAll(st, params)
                when (val src = x.sqlite3Step(st)) {
                    100 -> SqlResult(0, x.sqlite3ColumnInt64(st, 0), mem.readCString(x.sqlite3ColumnText(st, 0)))
                    101 -> SqlResult(0, 0L, "")
                    else -> SqlResult(src, 0L, errmsg(src))
                }
            } finally {
                x.sqlite3Reset(st)   // finalize 대신 reset — 다음 사용을 위해 보존
                if (params.isNotEmpty()) x.sqlite3ClearBindings(st)
            }
        }

        /** rc!=0 일 때 진단 메시지 (정상 경로 비용 0). */
        fun errmsg(rc: Int): String =
            if (rc == 0) "" else runCatching { mem.readCString(x.sqlite3Errmsg(db)) }.getOrDefault("?")

        /**
         * 일시적 rc 는 재시도: BUSY(5) = 락 경합, PROTOCOL(15) = WAL 락 프로토콜 내부 재시도(~100회) 고갈 —
         * 둘 다 트랜잭션 시작 전 실패라 재시도 안전.
         */
        fun run(sql: String, isQuery: Boolean, params: List<Any?> = emptyList()): SqlResult {
            // DML/DDL(비쿼리)도 단일 문장이면 캐시된 stmt 경로 — 반복 INSERT 의 prepare 낭비 제거.
            // 다중 문장(';')은 prepare 가 첫 문장만 보므로 sqlite3_exec 폴백 — 단 바인딩은 stmt 전용이라
            // params 가 있으면 무조건 stmt 경로 (다중 문장+바인딩 조합은 비지원: 첫 문장만 실행됨).
            val cacheable = isQuery || params.isNotEmpty() || !sql.contains(';')
            repeat(200) {
                val r = if (cacheable) query(sql, params)
                        else SqlResult(exec(sql), 0L, "")
                if (r.rc != 5 && r.rc != 15) return r
                Thread.sleep(5)
            }
            error("BUSY/PROTOCOL 지속: $sql")
        }

        /**
         * stmt 를 끝까지 step 하며 **전 행 디코드** — [queryRows]/[runUserRows] 공용 코어스닝 경로
         * (태스크 1건 = 결과 전체: 행당 큐 왕복 없음, 단일 태스크라 스냅샷 원자적, 워크스틸링 안전).
         * 값은 storage class 그대로: INTEGER→Long, FLOAT→Double, TEXT→String, BLOB→ByteArray, NULL→null.
         * @return null = BUSY(5)/PROTOCOL(15) — 호출측이 reset 후 재시도 (중간 행까지 갔어도 통째로 다시).
         */
        private fun decodeRows(st: Int): List<SqliteRow>? {
            val n = x.sqlite3ColumnCount(st)
            val cols = List(n) { mem.readCString(x.sqlite3ColumnName(st, it)) }
            val rows = ArrayList<SqliteRow>()
            while (true) {
                when (val src = x.sqlite3Step(st)) {
                    100 -> rows += SqliteRow(cols, Array(n) { i ->
                        when (x.sqlite3ColumnType(st, i)) {
                            1 -> x.sqlite3ColumnInt64(st, i)
                            2 -> x.sqlite3ColumnDouble(st, i)
                            // column_bytes 는 column_text/blob **뒤에** 호출 (sqlite 규약 — 변환 후 길이)
                            3 -> mem.readString(x.sqlite3ColumnText(st, i), x.sqlite3ColumnBytes(st, i))
                            4 -> mem.readBytes(x.sqlite3ColumnBlob(st, i), x.sqlite3ColumnBytes(st, i))
                            else -> null   // 5 = NULL
                        }
                    })
                    101 -> return rows
                    5, 15 -> return null
                    else -> error("query rc=$src (${errmsg(src)})")
                }
            }
        }

        /** 다중 행/열 SELECT — LRU 캐시 stmt + 바인딩 + 전 행 materialize. 오류는 예외 (rc 채널 없음). */
        fun queryRows(sql: String, params: List<Any?> = emptyList()): List<SqliteRow> {
            repeat(200) {
                val st = stmts[sql] ?: run {
                    val prc = x.sqlite3PrepareV2(db, sqlPtr(sql), -1, pp, 0)
                    val st2 = mem.readInt(pp)
                    if (prc == 5 || prc == 15) { Thread.sleep(5); return@repeat }
                    check(prc == 0 && st2 != 0) { "prepare rc=$prc (${errmsg(prc)}): $sql" }
                    stmts[sql] = st2
                    st2
                }
                val rows = try {
                    bindAll(st, params)
                    decodeRows(st)
                } finally {
                    x.sqlite3Reset(st)
                    if (params.isNotEmpty()) x.sqlite3ClearBindings(st)
                }
                if (rows != null) return rows
                Thread.sleep(5)
            }
            error("BUSY/PROTOCOL 지속: $sql")
        }

        /** 사용자 stmt 다중 행 — [runUser] 의 rows 버전 (재시도/재바인딩 시맨틱 동일). */
        fun runUserRows(st: Int, params: List<Any?>): List<SqliteRow> {
            check(st in userStmts) { "닫혔거나 이 워커 소유가 아닌 stmt" }
            repeat(200) {
                val rows = try {
                    bindAll(st, params)
                    decodeRows(st)
                } finally {
                    x.sqlite3Reset(st)
                }
                if (rows != null) return rows
                Thread.sleep(5)
            }
            error("BUSY/PROTOCOL 지속 (user stmt)")
        }

        /** 사용자 stmt prepare (캐시 밖, registry 등록). @return (stmt, ?-파라미터 개수) */
        fun prepareUser(sql: String): Pair<Int, Int> {
            val prc = x.sqlite3PrepareV2(db, sqlPtr(sql), -1, pp, 0)
            val st = mem.readInt(pp)
            if (prc != 0) {
                // rc 보존 (extended) — JDBC 가 [SQLITE_NOTADB] 같은 정밀 코드로 매핑할 수 있게
                throw SqliteNativeException(x.sqlite3ExtendedErrcode(db), "prepare (${errmsg(prc)}): $sql")
            }
            check(st != 0) { "prepare: 실행할 문장 없음: $sql" }
            userStmts += st
            return st to x.sqlite3BindParameterCount(st)
        }

        /** 사용자 stmt finalize — registry 에 있을 때만 (멱등: 이중 close 안전). */
        fun finalizeUser(st: Int) {
            if (userStmts.remove(st)) x.sqlite3Finalize(st)
        }

        /**
         * 사용자 stmt 실행 — 매번 [params] 전체 재바인딩이라 이전 값 잔존 없음 (clear_bindings 불필요).
         * BUSY/PROTOCOL 재시도는 [run] 과 동일 (reset 후 재바인딩 — bind 는 멱등).
         */
        fun runUser(st: Int, params: List<Any?>, isQuery: Boolean): SqlResult {
            check(st in userStmts) { "닫혔거나 이 워커 소유가 아닌 stmt" }
            repeat(200) {
                val r = try {
                    bindAll(st, params)
                    when (val src = x.sqlite3Step(st)) {
                        100 -> SqlResult(0, x.sqlite3ColumnInt64(st, 0), mem.readCString(x.sqlite3ColumnText(st, 0)))
                        101 -> SqlResult(0, 0L, "")
                        else -> SqlResult(src, 0L, errmsg(src))
                    }
                } finally {
                    x.sqlite3Reset(st)
                }
                if (r.rc != 5 && r.rc != 15) return r
                Thread.sleep(5)
            }
            error("BUSY/PROTOCOL 지속 (user stmt)")
        }
    }
}
