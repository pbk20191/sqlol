package io.roastedroot.sqlite4j.core

import io.roastedroot.sqlite4j.*
import io.roastedroot.sqlite4j.Function
import org.example.sqlite.CallbackEnv
import org.example.sqlite.JvmCallbacks
import org.example.sqlite.JvmCallbacks.Busy
import org.example.sqlite.JvmCallbacks.Udf
import org.example.sqlite.SqliteNativeException
import org.example.sqlite.WorkerDbPort
import org.example.sqlite.WorkerDbPort.Companion.openFile
import org.example.sqlite.WorkerDbPort.Companion.openMemory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.concurrent.Volatile

/**
 * xerial [DB] 절단면을 modules/sqlite 의 워커 아키텍처(wasm pthread + WAL) 위에 구현.
 * 
 * 
 * 모든 연산은 [WorkerDbPort](워커 1개 = sqlite 커넥션 1개)로 마샬링된다 — 큐가 직렬화하므로
 * 한 JDBC 커넥션 위의 동시 호출/다중 커서 인터리빙이 안전하다. 같은 파일을 여는 JDBC 커넥션들은
 * 런타임을 공유해 다중 커넥션 WAL 동시성(reader 동시 + writer 직렬)을 그대로 얻는다.
 * 
 * 
 * 콜백 계열(UDF/콜레이션/busy_handler/progress/커밋·업데이트 훅)은 helpers.c env 테이블 +
 * user_data 레지스트리(JvmCallbacks)로, backup/restore/serialize 는 직접 API + guest "/tmp" 중계로
 * 구현 — xerial DB 표면 완전 동등 (BUILD.md §11.2/§11.3).
 */
class WorkerDB(url: String, fileName: String, config: SQLiteConfig, private val memory: Boolean) :
    DB(url, fileName, config) {
    @Volatile
    private var port: WorkerDbPort? = null

    private fun interface Op<T> {
        @Throws(Exception::class)
        fun run(): T
    }

    /** 포트 호출 공통 래핑 — 워커 타임아웃/종료(IllegalState)·ExecutionException 을 SQLException 으로.  */
    @Throws(SQLException::class)
    private fun <T> call(op: Op<T?>): T? {
        try {
            return op.run()
        } catch (e: SQLException) {
            throw e
        } catch (e: Exception) {
            // rc 보존 예외(open 등)는 정밀 SQLITE_* 코드로 (예: rc=14 → [SQLITE_CANTOPEN])
            var rcEx: Throwable? = e
            while (rcEx != null && rcEx !is SqliteNativeException) {
                rcEx = rcEx.cause
            }
            if (rcEx != null) {
                val ne =
                    rcEx
                throw DB.newSQLException(ne.rc, ne.message!!)
            }
            throw SQLException(e.message, e)
        }
    }

    @Throws(SQLException::class)
    private fun port(): WorkerDbPort {
        val p = port
        if (p == null) throw SQLException("database not open")
        return p
    }

    @Synchronized
    @Throws(SQLException::class)
    override fun _open(filename: String, openFlags: Int) {
        if (port != null) throw SQLException("already open: " + filename)
        val readOnly = (openFlags and OPEN_READONLY) != 0
        if (!memory) {
            val p = Path.of(filename)
            if (!Files.exists(p)) {
                if ((openFlags and OPEN_CREATE) == 0) {
                    throw SQLException("Database file doesn't exists: " + filename)
                }
                val parent = p.toAbsolutePath().getParent()
                try {
                    if (parent != null) Files.createDirectories(parent)
                } catch (e: Exception) {
                    throw SQLException("Failed to create db file: " + filename, e)
                }
            }
        }
        port =
            call<WorkerDbPort>(
                WorkerDB.Op {
                    if (memory)
                        openMemory(readOnly)
                    else
                        openFile(Path.of(filename), readOnly)
                })
    }

    @Synchronized
    @Throws(SQLException::class)
    override fun _close() {
        val p = port
        if (p != null) {
            port = null
            val keys = intArrayOf(busyKey, progressKey, commitKey, rollbackKey, updateKey)
            updateKey = 0
            rollbackKey = updateKey
            commitKey = rollbackKey
            progressKey = commitKey
            busyKey = progressKey
            // close 유발 롤백(열린 tx)은 리스너에 전달하지 않는다 — 업스트림 WasmDB._close 시맨틱 미러
            // (훅 자체는 fire 하지만 빈 집합 순회 = no-op)
            updateListeners.clear()
            commitListeners.clear()
            try {
                // 먼저 guest close — sqlite3_close 가 열린 트랜잭션을 롤백하며 xRollback 등 훅을
                // **정당하게** 호출할 수 있으므로, 레지스트리 키는 그때까지 살아 있어야 한다.
                // (구 큐 모델에선 이 순서 버그가 워커 VT 의 uncaughtHandler 로 조용히 삼켜졌었다 — §9.2)
                call<Any>(
                    WorkerDB.Op {
                        p.close()
                        null
                    })
            } finally {
                // 키 해제 + 0 (WasmDBHelper 가시성 계약)
                for (k in keys) {
                    if (k != 0) p.callbacks.free(k)
                }
            }
        }
    }

    // ---- 커넥션 수준 ----
    @Throws(SQLException::class)
    public override fun interrupt() {
        // 워커 큐를 거치지 않는 유일한 연산 — 실행 중(블록된) 문장도 풀어야 하므로
        port().interrupt()
    }

    @Throws(SQLException::class)
    public override fun busy_timeout(ms: Int) {
        call<Int>(WorkerDB.Op { port().busyTimeout(ms) })
    }

    @Synchronized
    @Throws(SQLException::class)
    public override fun busy_handler(busyHandler: BusyHandler?) {
        val p = port()
        val old = busyKey
        if (busyHandler == null) {
            busyKey = 0
            call<Int>(WorkerDB.Op { p.busyHandler(0) })
        } else {
            busyKey =
                p.callbacks
                    .register(
                        Busy { nPrev: Int ->
                            try {
                                return@Busy busyHandler.callback(nPrev)
                            } catch (e: SQLException) {
                                sneakyThrow<RuntimeException>(e)
                                return@Busy 0
                            }
                        })
            val key = busyKey
            call<Int>(WorkerDB.Op { p.busyHandler(key) })
        }
        if (old != 0) p.callbacks.free(old)
    }

    @Throws(SQLException::class)
    override fun errmsg(): String {
        return call<String>(WorkerDB.Op { port().errmsg() })!!
    }

    @Throws(SQLException::class)
    public override fun libversion(): String {
        return call<String>(WorkerDB.Op { port().libversion() })!!
    }

    @Throws(SQLException::class)
    public override fun changes(): Long {
        return call<Long>(WorkerDB.Op { port().changes() })!!
    }

    @Throws(SQLException::class)
    public override fun total_changes(): Long {
        return call<Long>(WorkerDB.Op { port().totalChanges() })!!
    }

    @Throws(SQLException::class)
    public override fun shared_cache(enable: Boolean): Int {
        return Codes.SQLITE_OK // 인스턴스-per-커넥션 모델엔 의미 없음 — no-op
    }

    @Throws(SQLException::class)
    public override fun enable_load_extension(enable: Boolean): Int {
        return Codes.SQLITE_OK // WASI 엔 동적 .so 없음 (정적 컴파일 확장은 전부 내장) — no-op
    }

    @Throws(SQLException::class)
    public override fun _exec(sql: String): Int {
        return call<Int>(WorkerDB.Op { port().exec(sql) })!!
    }

    @Throws(SQLException::class)
    public override fun limit(id: Int, value: Int): Int {
        return call<Int>(WorkerDB.Op { port().limit(id, value) })!!
    }

    // ---- stmt 수명/실행 ----
    @Throws(SQLException::class)
    override fun prepare(sql: String): SafeStmtPtr {
        val st = call<Int>(WorkerDB.Op { port().prepare(sql) })!!
        return SafeStmtPtr(this, st)
    }

    @Throws(SQLException::class)
    override fun finalize(stmt: Long): Int {
        return call<Int>(WorkerDB.Op { port().finalizeStmt(stmt.toInt()) })!!
    }

    @Throws(SQLException::class)
    public override fun step(stmt: Long): Int {
        return call<Int>(WorkerDB.Op { port().step(stmt.toInt()) })!!
    }

    @Throws(SQLException::class)
    public override fun reset(stmt: Long): Int {
        return call<Int>(WorkerDB.Op { port().reset(stmt.toInt()) })!!
    }

    @Throws(SQLException::class)
    public override fun clear_bindings(stmt: Long): Int {
        return call<Int>(WorkerDB.Op { port().clearBindings(stmt.toInt()) })!!
    }

    @Throws(SQLException::class)
    override fun bind_parameter_count(stmt: Long): Int {
        return call<Int>(WorkerDB.Op { port().bindParameterCount(stmt.toInt()) })!!
    }

    // ---- 컬럼 ----
    @Throws(SQLException::class)
    public override fun column_count(stmt: Long): Int {
        return call<Int>(WorkerDB.Op { port().columnCount(stmt.toInt()) })!!
    }

    @Throws(SQLException::class)
    public override fun column_type(stmt: Long, col: Int): Int {
        return call<Int>(WorkerDB.Op { port().columnType(stmt.toInt(), col) })!!
    }

    @Throws(SQLException::class)
    public override fun column_decltype(stmt: Long, col: Int): String? {
        return call<String?>(WorkerDB.Op { port().columnDecltype(stmt.toInt(), col) })
    }

    @Throws(SQLException::class)
    public override fun column_table_name(stmt: Long, col: Int): String? {
        return call<String?>(WorkerDB.Op { port().columnTableName(stmt.toInt(), col) })
    }

    @Throws(SQLException::class)
    public override fun column_name(stmt: Long, col: Int): String {
        return call<String?>(WorkerDB.Op { port().columnName(stmt.toInt(), col) })!!
    }

    @Throws(SQLException::class)
    public override fun column_text(stmt: Long, col: Int): String? {
        return call<String?>(WorkerDB.Op { port().columnText(stmt.toInt(), col) })
    }

    @Throws(SQLException::class)
    public override fun column_blob(stmt: Long, col: Int): ByteArray? {
        return call<ByteArray?>(WorkerDB.Op { port().columnBlob(stmt.toInt(), col) })
    }

    @Throws(SQLException::class)
    public override fun column_double(stmt: Long, col: Int): Double {
        return call<Double>(WorkerDB.Op { port().columnDouble(stmt.toInt(), col) })!!
    }

    @Throws(SQLException::class)
    public override fun column_long(stmt: Long, col: Int): Long {
        return call<Long>(WorkerDB.Op { port().columnLong(stmt.toInt(), col) })!!
    }

    @Throws(SQLException::class)
    public override fun column_int(stmt: Long, col: Int): Int {
        return call<Int>(WorkerDB.Op { port().columnInt(stmt.toInt(), col) })!!
    }

    @Throws(SQLException::class)
    override fun column_metadata(stmt: Long): Array<BooleanArray> {
        return call<Array<BooleanArray>>(WorkerDB.Op { port().columnMetadata(stmt.toInt()) })!!
    }

    // ---- 바인딩 (타입 디스패치/TRANSIENT/스크래치는 포트 너머 Session 공통) ----
    @Throws(SQLException::class)
    override fun bind_null(stmt: Long, pos: Int): Int {
        return call<Int>(WorkerDB.Op { port().bind(stmt.toInt(), pos, null) })!!
    }

    @Throws(SQLException::class)
    override fun bind_int(stmt: Long, pos: Int, v: Int): Int {
        return call<Int>(WorkerDB.Op { port().bind(stmt.toInt(), pos, v) })!!
    }

    @Throws(SQLException::class)
    override fun bind_long(stmt: Long, pos: Int, v: Long): Int {
        return call<Int>(WorkerDB.Op { port().bind(stmt.toInt(), pos, v) })!!
    }

    @Throws(SQLException::class)
    override fun bind_double(stmt: Long, pos: Int, v: Double): Int {
        return call<Int>(WorkerDB.Op { port().bind(stmt.toInt(), pos, v) })!!
    }

    @Throws(SQLException::class)
    override fun bind_text(stmt: Long, pos: Int, v: String): Int {
        return call<Int>(WorkerDB.Op { port().bind(stmt.toInt(), pos, v) })!!
    }

    @Throws(SQLException::class)
    override fun bind_blob(stmt: Long, pos: Int, v: ByteArray): Int {
        return call<Int>(WorkerDB.Op { port().bind(stmt.toInt(), pos, v) })!!
    }

    /** UDF 콜백이 실행 중인 동안의 호출 컨텍스트 — 이 커넥션의 워커 스레드만 만진다 (UDF 는 step 스레드 전용).  */
    @Volatile
    private var activeEnv: CallbackEnv? = null

    /** name → (registry key, nArgs) — destroy 시 같은 nArgs 로 NULL 재등록해야 정확히 교체된다.  */
    private val functionKeys: MutableMap<String, IntArray> = HashMap<String, IntArray>()

    private val collationKeys: MutableMap<String, Int> = HashMap<String, Int>()
    private var busyKey = 0
    private var progressKey = 0
    private var commitKey = 0
    private var rollbackKey = 0
    private var updateKey = 0

    @Throws(SQLException::class)
    private fun env(): CallbackEnv {
        val e = activeEnv
        if (e == null) throw SQLException("UDF 콜백 실행 중이 아님")
        return e
    }

    @Throws(SQLException::class)
    private fun valuePtr(f: Function, arg: Int): Int {
        return env().deref(f.getValueArg(arg).toInt())
    }

    /** xerial Function → JvmCallbacks.Udf 어댑터 (WasmDB 디스패치와 동일: context/value/args 주입).  */
    private inner class UdfAdapter(private val f: Function) : Udf {
        fun invoke(
            env: CallbackEnv,
            ctx: Int,
            argc: Int,
            argv: Int,
            call: Runnable
        ) {
            activeEnv = env
            f.setContext(ctx.toLong())
            f.setValue(argv.toLong())
            f.setArgs(argc)
            call.run()
        }

        override fun xFunc(env: CallbackEnv, ctx: Int, argc: Int, argv: Int) {
            invoke(env, ctx, argc, argv, Runnable {
                try {
                    f.xFunc()
                } catch (e: SQLException) {
                    sneakyThrow<RuntimeException>(e)
                }
            })
        }

        override fun xStep(env: CallbackEnv, ctx: Int, argc: Int, argv: Int) {
            invoke(env, ctx, argc, argv, Runnable {
                try {
                    (f as Function.Aggregate).xStep()
                } catch (e: SQLException) {
                    sneakyThrow<RuntimeException>(e)
                }
            })
        }

        override fun xFinal(env: CallbackEnv, ctx: Int) {
            activeEnv = env
            f.setContext(ctx.toLong())
            try {
                (f as Function.Aggregate).xFinal()
            } catch (e: SQLException) {
                sneakyThrow<RuntimeException>(e)
            }
        }

        override fun xValue(env: CallbackEnv, ctx: Int) {
            activeEnv = env
            f.setContext(ctx.toLong())
            try {
                (f as Function.Window).xValue()
            } catch (e: SQLException) {
                sneakyThrow<RuntimeException>(e)
            }
        }

        override fun xInverse(env: CallbackEnv, ctx: Int, argc: Int, argv: Int) {
            invoke(env, ctx, argc, argv, Runnable {
                try {
                    (f as Function.Window).xInverse()
                } catch (e: SQLException) {
                    sneakyThrow<RuntimeException>(e)
                }
            })
        }
    }

    @Throws(SQLException::class)
    public override fun result_null(context: Long) {
        env().resultNull(context.toInt())
    }

    @Throws(SQLException::class)
    public override fun result_text(context: Long, `val`: String) {
        env().resultText(context.toInt(), `val`)
    }

    @Throws(SQLException::class)
    public override fun result_blob(context: Long, `val`: ByteArray) {
        env().resultBlob(context.toInt(), `val`)
    }

    @Throws(SQLException::class)
    public override fun result_double(context: Long, `val`: Double) {
        env().resultDouble(context.toInt(), `val`)
    }

    @Throws(SQLException::class)
    public override fun result_long(context: Long, `val`: Long) {
        env().resultLong(context.toInt(), `val`)
    }

    @Throws(SQLException::class)
    public override fun result_int(context: Long, `val`: Int) {
        env().resultLong(context.toInt(), `val`.toLong())
    }

    @Throws(SQLException::class)
    public override fun result_error(context: Long, err: String) {
        env().resultError(context.toInt(), err)
    }

    @Throws(SQLException::class)
    public override fun value_text(f: Function, arg: Int): String? {
        return env().valueText(valuePtr(f, arg))
    }

    @Throws(SQLException::class)
    public override fun value_blob(f: Function, arg: Int): ByteArray? {
        return env().valueBlob(valuePtr(f, arg))
    }

    @Throws(SQLException::class)
    public override fun value_double(f: Function, arg: Int): Double {
        return env().valueDouble(valuePtr(f, arg))
    }

    @Throws(SQLException::class)
    public override fun value_long(f: Function, arg: Int): Long {
        return env().valueLong(valuePtr(f, arg))
    }

    @Throws(SQLException::class)
    public override fun value_int(f: Function, arg: Int): Int {
        return env().valueInt(valuePtr(f, arg))
    }

    @Throws(SQLException::class)
    public override fun value_type(f: Function, arg: Int): Int {
        return env().valueType(valuePtr(f, arg))
    }

    @Synchronized
    @Throws(SQLException::class)
    public override fun create_function(name: String, f: Function, nArgs: Int, flags: Int): Int {
        val p = port()
        val kind =
            if (f is Function.Window)
                WorkerDbPort.FnKind.WINDOW
            else
                if (f is Function.Aggregate)
                    WorkerDbPort.FnKind.AGGREGATE
                else
                    WorkerDbPort.FnKind.SCALAR
        val key = p.callbacks.register(UdfAdapter(f))
        val rc = call<Int>(WorkerDB.Op {
            p.createFunction(
                name,
                nArgs,
                WorkerDB.Companion.UTF8 or flags,
                key,
                kind
            )
        })!!
        if (rc == Codes.SQLITE_OK) {
            functionKeys.put(name, intArrayOf(key, nArgs))
        } else {
            p.callbacks.free(key)
        }
        return rc
    }

    @Synchronized
    @Throws(SQLException::class)
    public override fun destroy_function(name: String): Int {
        val p = port()
        val meta = functionKeys.remove(name)
        val nArgs = if (meta != null) meta[1] else 0
        // 같은 name+nArgs 의 NULL 재등록 → sqlite 가 xDestroy(key) 호출 → 레지스트리 자동 해제
        return call<Int>(WorkerDB.Op {
            p.destroyFunction(
                name,
                nArgs,
                WorkerDB.Companion.UTF8
            )
        })!!
    }

    @Synchronized
    @Throws(SQLException::class)
    public override fun create_collation(name: String, c: Collation): Int {
        val p = port()
        val key =
            p.callbacks
                .register(
                    JvmCallbacks.Collation { a: ByteArray?, b: ByteArray? ->
                        c.xCompare(
                            kotlin.text.String(a!!, StandardCharsets.UTF_8),
                            kotlin.text.String(b!!, StandardCharsets.UTF_8)
                        )
                    })
        val rc = call<Int>(WorkerDB.Op { p.createCollation(name, WorkerDB.Companion.UTF8, key) })!!
        if (rc == Codes.SQLITE_OK) {
            collationKeys.put(name, key)
        } else {
            p.callbacks.free(key)
        }
        return rc
    }

    @Synchronized
    @Throws(SQLException::class)
    public override fun destroy_collation(name: String): Int {
        val p = port()
        collationKeys.remove(name)
        // NULL 재등록 → xDestroyCollation(oldKey) → 레지스트리 자동 해제
        return call<Int>(WorkerDB.Op { p.destroyCollation(name, WorkerDB.Companion.UTF8) })!!
    }

    @Throws(SQLException::class)
    public override fun backup(
        dbName: String,
        destFileName: String,
        observer: ProgressObserver?
    ): Int {
        return backup(
            dbName,
            destFileName,
            observer,
            DEFAULT_BACKUP_BUSY_SLEEP_TIME_MILLIS,
            DEFAULT_BACKUP_NUM_BUSY_BEFORE_FAIL,
            DEFAULT_PAGES_PER_BACKUP_STEP
        )
    }

    @Throws(SQLException::class)
    public override fun backup(
        dbName: String,
        destFileName: String,
        observer: ProgressObserver?,
        sleepTimeMillis: Int,
        nTimeouts: Int,
        pagesPerStep: Int
    ): Int {
        val p = port()
        // sqlite3_open 시맨틱: 대상의 부모 디렉터리가 없으면 CANTOPEN (만들어 주지 않는다)
        val destPath = Path.of(destFileName)
        val destParent = destPath.toAbsolutePath().getParent()
        if (destParent == null || !Files.isDirectory(destParent)) {
            throw newSQLException(Codes.SQLITE_CANTOPEN, "unable to open database file: " + destFileName)
        }
        return call<Int>(
            WorkerDB.Op {
                p.backup(
                    dbName,
                    destPath,
                    if (observer == null) null else org.example.sqlite.WorkerDbPort.BackupObserver { remaining: kotlin.Int, pageCount: kotlin.Int ->
                        observer.progress(
                            remaining,
                            pageCount
                        )
                    },
                    sleepTimeMillis,
                    nTimeouts,
                    pagesPerStep
                )
            })!!
    }

    @Throws(SQLException::class)
    public override fun restore(
        dbName: String,
        sourceFileName: String,
        observer: ProgressObserver?
    ): Int {
        return restore(
            dbName,
            sourceFileName,
            observer,
            DEFAULT_BACKUP_BUSY_SLEEP_TIME_MILLIS,
            DEFAULT_BACKUP_NUM_BUSY_BEFORE_FAIL,
            DEFAULT_PAGES_PER_BACKUP_STEP
        )
    }

    @Throws(SQLException::class)
    public override fun restore(
        dbName: String,
        sourceFileName: String,
        observer: ProgressObserver?,
        sleepTimeMillis: Int,
        nTimeouts: Int,
        pagesPerStep: Int
    ): Int {
        val p = port()
        // 복원 소스가 없으면 CANTOPEN — 파일을 만들어선 안 된다 (sqlite3_open READONLY 시맨틱)
        val srcPath = Path.of(sourceFileName)
        if (!Files.isReadable(srcPath)) {
            throw newSQLException(Codes.SQLITE_CANTOPEN, "unable to open database file: " + sourceFileName)
        }
        return call<Int>(
            WorkerDB.Op {
                p.restore(
                    dbName,
                    srcPath,
                    if (observer == null) null else org.example.sqlite.WorkerDbPort.BackupObserver { remaining: kotlin.Int, pageCount: kotlin.Int ->
                        observer.progress(
                            remaining,
                            pageCount
                        )
                    },
                    sleepTimeMillis,
                    nTimeouts,
                    pagesPerStep
                )
            })!!
    }

    @Synchronized
    @Throws(SQLException::class)
    public override fun register_progress_handler(vmCalls: Int, progressHandler: ProgressHandler?) {
        if (progressHandler == null) {   // ProgressHandler.setHandler(conn, n, null) = 해제
            clear_progress_handler()
            return
        }
        val p = port()
        val old = progressKey
        progressKey =
            p.callbacks
                .register(
                    JvmCallbacks.Progress {
                        try {
                            return@Progress progressHandler.progress()
                        } catch (e: SQLException) {
                            sneakyThrow<RuntimeException>(e)
                            return@Progress 0
                        }
                    })
        val key = progressKey
        call<Any>(WorkerDB.Op {
            p.progressHandler(vmCalls, key)
            null
        })
        if (old != 0) p.callbacks.free(old)
    }

    @Synchronized
    @Throws(SQLException::class)
    public override fun clear_progress_handler() {
        val p = port()
        val old = progressKey
        progressKey = 0
        call<Any>(WorkerDB.Op {
            p.progressHandler(0, 0)
            null
        })
        if (old != 0) p.callbacks.free(old)
    }

    @Synchronized
    override fun set_commit_listener(enabled: Boolean) {
        try {
            val p = port()
            if (enabled) {
                commitKey =
                    p.callbacks
                        .register(
                            JvmCallbacks.Commit {
                                onCommit(true)
                                0
                            })
                rollbackKey =
                    p.callbacks
                        .register(JvmCallbacks.Rollback { onCommit(false) })
                val ck = commitKey
                val rk = rollbackKey
                call<Any>(WorkerDB.Op {
                    p.commitHooks(ck, rk)
                    null
                })
            } else {
                val ck = commitKey
                val rk = rollbackKey
                commitKey = 0
                rollbackKey = 0
                call<Any>(WorkerDB.Op {
                    p.commitHooks(0, 0)
                    null
                })
                if (ck != 0) p.callbacks.free(ck)
                if (rk != 0) p.callbacks.free(rk)
            }
        } catch (e: SQLException) {
            sneakyThrow<RuntimeException>(e)
        }
    }

    @Synchronized
    override fun set_update_listener(enabled: Boolean) {
        try {
            val p = port()
            if (enabled) {
                updateKey =
                    p.callbacks
                        .register(
                            JvmCallbacks.Update { op: Int, dbName: String?, table: String?, rowId: Long ->
                                onUpdate(
                                    op,
                                    dbName!!,
                                    table!!,
                                    rowId
                                )
                            })
                val key = updateKey
                call<Any>(WorkerDB.Op {
                    p.updateHook(key)
                    null
                })
            } else {
                val key = updateKey
                updateKey = 0
                call<Any>(WorkerDB.Op {
                    p.updateHook(0)
                    null
                })
                if (key != 0) p.callbacks.free(key)
            }
        } catch (e: SQLException) {
            sneakyThrow<RuntimeException>(e)
        }
    }

    val progressHandler: Long
        // ---- 테스트 가시성 (코퍼스 WasmDBHelper 대응): 현재 등록된 훅의 레지스트리 키 (0 = 없음) ----
        get() = progressKey.toLong()

    val busyHandler: Long
        get() = busyKey.toLong()

    val commitListener: Long
        get() = commitKey.toLong()

    val updateListener: Long
        get() = updateKey.toLong()

    @Throws(SQLException::class)
    public override fun serialize(schema: String): ByteArray {
        val result: ByteArray = call<ByteArray?>(WorkerDB.Op { port().serialize(schema) })!!
        if (result == null) {
            throw SQLException("serialize 실패: schema=" + schema)
        }
        return result
    }

    @Throws(SQLException::class)
    public override fun deserialize(schema: String, buff: ByteArray) {
        val rc = call<Int>(WorkerDB.Op { port().deserialize(schema, buff) })!!
        if (rc != Codes.SQLITE_OK) {
            throw newSQLException(rc, errmsg())
        }
    }

    companion object {
        /** 벤더링된 SQLiteJDBCLoader 가 드라이버 버전으로 사용 (sqlite 아말감 버전 고정).  */
        @JvmStatic
        fun version(): String {
            return "3.53.0"
        }

        // ---- 수명 ----
        /** SQLiteOpenMode 비트 (sqlite3_open_v2 와 동일).  */
        private const val OPEN_READONLY = 0x1
        private const val OPEN_CREATE = 0x4

        // ---- 콜백 계열 (helpers.c env 테이블 + user_data 레지스트리 — BUILD.md §10 ①) ----
        //
        // 실행 경로: 워커 스레드의 wasm step 안에서 env import → JvmVfsRuntime 호스트 함수 →
        // JvmCallbacks 레지스트리(user_data 키) → 아래 어댑터. value_*/result_* 는 그 스레드 위의
        // 재진입 export 호출이라 **큐를 타지 않는다** (자기 큐 제출 = 데드락).
        /** SQLITE_UTF8 — 등록 인코딩 (xerial flags 와 OR).  */
        private const val UTF8 = 1

//        @Throws(E::class)
        private fun <E : Throwable> sneakyThrow(e: Throwable) {
            throw e
        }

        // WasmDB 기본값 미러
        private const val DEFAULT_BACKUP_BUSY_SLEEP_TIME_MILLIS = 100
        private const val DEFAULT_BACKUP_NUM_BUSY_BEFORE_FAIL = 3
        private const val DEFAULT_PAGES_PER_BACKUP_STEP = 100
    }
}
