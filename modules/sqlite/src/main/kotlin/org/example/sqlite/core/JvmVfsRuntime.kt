package org.example.sqlite.core

import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportMemory
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.runtime.WasmFunctionHandle
import com.dylibso.chicory.wasm.types.ExternalType
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.dylibso.chicory.wasm.types.ValType
import com.dylibso.chicory.wasi.WasiOptions
import com.dylibso.chicory.wasi.WasiPreview1
import com.example.wasm.JvmVfsModule
import com.example.wasm.JvmVfsModule_ModuleExports
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.function.Consumer
import java.util.function.LongConsumer
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.concurrent.write

/**
 * `wasm32-wasi-threads` SQLite + 커스텀 JVM VFS 런타임 (다중 커넥션 WAL).
 *
 * Chicory **AOT** 모듈([JvmVfsModule]) + 컴파일타임 타입드 export 인터페이스
 * ([JvmVfsModule_ModuleExports]) 기반. 공유 linear memory 1개([StatelessBulkMemory])를 모든
 * 인스턴스가 공유하며:
 *  - 파일 I/O 는 WASI 위임(RW-락 정책), 락/shm 은 [JvmVfsLocks] + [ShmArena] (JVM 인메모리)로
 *    처리하는 커스텀 VFS(`os_jvm`)
 *  - 워커는 wasm 내부 `pthread_create` → `wasi.thread-spawn` import → `wasi_thread_start` 정석 부트스트랩
 *
 * 생성 = 배선: 프로퍼티 초기화 순서가 곧 부팅 순서다 (공유메모리 → WASI → 디스패처 → main 인스턴스
 * → `_initialize` → `install_jvm_vfs` → shm arena). 순수 JVM (네이티브/JNI 없음).
 *
 * @param preopens guest 경로 → host 디렉터리 (예: "/db" to dbDir)
 */
class JvmVfsRuntime(preopens: Map<String, Path> = emptyMap()) : AutoCloseable {

    /** 공유 linear memory — 벌크 연산이 stateless 라 동시 접근 안전 (§9.1 함정 3 해소의 기반). */
    val mem: Memory = StatelessBulkMemory(ByteBufferMemory(MemoryLimits(INITIAL_PAGES, MAX_PAGES, true)))

    internal val locks = JvmVfsLocks()
    internal val arena = ShmArena(mem)
    val workerErrors = ConcurrentLinkedQueue<Throwable>()

    /** 하이브리드 디스패처가 진짜 spawn 으로 위임한 횟수 (child 내부 pthread_create — 예: sorter 보조 스레드). */
    val delegatedSpawns = AtomicInteger()
    val wasiLock = ReentrantReadWriteLock()

    /** wasm→JVM 콜백 레지스트리 (UDF/콜레이션/훅 — user_data 키 디스패치, §10 ① helpers.c 짝). */
    val callbacks = JvmCallbacks()

    /** 호출 인스턴스 → [CallbackEnv] 캐시 — exports 파사드 생성(이름 조회 수백 건)이 비싸 인스턴스당 1회.
     *  weak: 워커/sorter 인스턴스는 커넥션 churn 으로 계속 생기고 죽는다. */
    private val envCache = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Instance, CallbackEnv>()
    )

    private fun envOf(i: Instance): CallbackEnv =
        envCache.computeIfAbsent(i) { CallbackEnv(JvmVfsModule_ModuleExports(it), it.memory()) }

    private val wasi: WasiPreview1 = WasiPreview1.builder()
        .withOptions(
            WasiOptions.builder()
                .inheritSystem()
                .withRandom(ThreadLocalRandom.current())
                .apply { preopens.forEach { (guest, host) -> withDirectory(guest, host) } }
                .build()
        )
        .build()

    private val module = JvmVfsModule.load()

    /** 함수 인덱스 공간에서 `wasi.thread-spawn` 의 인덱스 (import 가 0..N-1 선점) — 테이블 ref 대상. */
    private val spawnFuncIdx: Int = run {
        var fi = 0
        val impSec = module.importSection()
        for (k in 0 until impSec.importCount()) {
            val im = impSec.getImport(k)
            if (im.importType() == ExternalType.FUNCTION) {
                if (im.module() == "wasi" && im.name() == "thread-spawn") return@run fi
                fi++
            }
        }
        error("thread-spawn import 못 찾음")
    }

    private val spawnHandle = SpawnWorkerHandle(delegatedSpawns, spawnFuncIdx) { t, e ->
        workerErrors.add(e)
    }

    val main: Instance = Instance.builder(module)
        .withMachineFactory(JvmVfsModule::create)
        .withImportValues(buildImports())
        .withStart(false).withInitialize(true).build()

    val exports = JvmVfsModule_ModuleExports(main)

    init {
        exports._initialize()
        check(exports.installJvmVfs() == 0) { "install_jvm_vfs 실패" }
        arena.base = exports.malloc(ARENA_BYTES)
    }

    /** helpers.c 콜백의 테이블 슬롯 (wasm 함수 포인터 = 테이블 인덱스) — create_function 등에 전달. */
    class CallbackPtrs(
        val xFunc: Int, val xStep: Int, val xFinal: Int, val xValue: Int, val xInverse: Int,
        val xDestroy: Int, val xCompare: Int, val xDestroyCollation: Int,
        val xBusy: Int, val xProgress: Int, val xCommit: Int, val xRollback: Int, val xUpdate: Int,
    )

    val cbPtrs: CallbackPtrs by lazy {
        CallbackPtrs(
            exports.xFuncPtr(), exports.xStepPtr(), exports.xFinalPtr(), exports.xValuePtr(),
            exports.xInversePtr(), exports.xDestroyPtr(), exports.xComparePtr(),
            exports.xDestroyCollationPtr(), exports.xBusyPtr(), exports.xProgressPtr(),
            exports.xCommitPtr(), exports.xRollbackPtr(), exports.xUpdatePtr(),
        )
    }

    /** WASI(RW-락) + thread-spawn 디스패처 + VFS(락/shm) + 공유메모리 import 배선. */
    private fun buildImports(): ImportValues {
        // i.memory() = StatelessBulkMemory → readCString 이 이미 단건 absolute (§9.1 함정 3 해소)
        fun cstr(i: Instance, p: Long) = i.memory().readCString(p.toInt())
        fun hf(name: String, np: Int, body: (Instance, LongArray) -> Long) =
            HostFunction("vfs", name, List(np) { ValType.I32 }, listOf(ValType.I32)) { i, a ->
                longArrayOf(body(i, a))
            }

        val vfsImports = arrayOf(
            hf("lock", 3) { i, a -> locks.lock(a[0].toInt(), cstr(i, a[1]), a[2].toInt()).toLong() },
            hf("unlock", 3) { i, a -> locks.unlock(a[0].toInt(), cstr(i, a[1]), a[2].toInt()).toLong() },
            hf("check_reserved", 2) { i, a -> locks.checkReserved(a[0].toInt(), cstr(i, a[1])).toLong() },
            hf("shm_map", 4) { i, a -> arena.map(cstr(i, a[0]), a[1].toInt(), a[2].toInt(), a[3].toInt()).toLong() },
            hf("shm_lock", 5) { i, a -> locks.shmLock(a[0].toInt(), cstr(i, a[1]), a[2].toInt(), a[3].toInt(), a[4].toInt()).toLong() },
            hf("shm_unmap", 3) { i, a ->
                val n = cstr(i, a[1])
                locks.shmUnmap(a[0].toInt(), n)
                if (a[2].toInt() != 0) arena.dropFile(n)
                0L
            },
        )

        val threadSpawn = HostFunction("wasi", "thread-spawn", listOf(ValType.I32), listOf(ValType.I32), spawnHandle)

        // helpers.c 콜백 13종 (env.*) — user_data 키로 레지스트리 디스패치. 호출 스레드는 그 커넥션의
        // 워커 VT(UDF/훅) 또는 sorter VT(콜레이션 가능, §9.1) — 레지스트리/CallbackEnv 모두 스레드 무관.
        // 콜백의 JVM 예외는 wasm 프레임을 관통해 step 호출자에게 전파된다 (sqlite4j 와 동일 시맨틱).
        val i32 = ValType.I32
        val i64 = ValType.I64
        val cb = callbacks
        fun udfOf(env: CallbackEnv, ctx: Int) = cb.udf(env.userData(ctx))
        val callbackImports = arrayOf(
            HostFunction("env", "xFunc", listOf(i32, i32, i32), emptyList<ValType>()) { i, a ->
                val env = envOf(i); val ctx = a[0].toInt()
                udfOf(env, ctx).xFunc(env, ctx, a[1].toInt(), a[2].toInt()); null
            },
            HostFunction("env", "xStep", listOf(i32, i32, i32), emptyList<ValType>()) { i, a ->
                val env = envOf(i); val ctx = a[0].toInt()
                udfOf(env, ctx).xStep(env, ctx, a[1].toInt(), a[2].toInt()); null
            },
            HostFunction("env", "xFinal", listOf(i32), emptyList<ValType>()) { i, a ->
                val env = envOf(i); val ctx = a[0].toInt()
                udfOf(env, ctx).xFinal(env, ctx); null
            },
            HostFunction("env", "xValue", listOf(i32), emptyList<ValType>()) { i, a ->
                val env = envOf(i); val ctx = a[0].toInt()
                udfOf(env, ctx).xValue(env, ctx); null
            },
            HostFunction("env", "xInverse", listOf(i32, i32, i32), emptyList<ValType>()) { i, a ->
                val env = envOf(i); val ctx = a[0].toInt()
                udfOf(env, ctx).xInverse(env, ctx, a[1].toInt(), a[2].toInt()); null
            },
            // sqlite 가 함수 교체/db close 시 호출하는 destructor — 레지스트리 자동 해제 통로
            HostFunction("env", "xDestroy", listOf(i32), emptyList<ValType>()) { _, a ->
                cb.free(a[0].toInt()); null
            },
            HostFunction("env", "xCompare", listOf(i32, i32, i32, i32, i32), listOf(i32)) { i, a ->
                val m = i.memory()
                val r = cb.collation(a[0].toInt())
                    .compare(m.readBytes(a[2].toInt(), a[1].toInt()), m.readBytes(a[4].toInt(), a[3].toInt()))
                longArrayOf(r.toLong())
            },
            HostFunction("env", "xDestroyCollation", listOf(i32), emptyList<ValType>()) { _, a ->
                cb.free(a[0].toInt()); null
            },
            HostFunction("env", "xBusy", listOf(i32, i32), listOf(i32)) { _, a ->
                longArrayOf(cb.busy(a[0].toInt()).callback(a[1].toInt()).toLong())
            },
            HostFunction("env", "xProgress", listOf(i32), listOf(i32)) { _, a ->
                longArrayOf(cb.progress(a[0].toInt()).progress().toLong())
            },
            HostFunction("env", "xCommit", listOf(i32), listOf(i32)) { _, a ->
                longArrayOf(cb.commit(a[0].toInt()).commit().toLong())
            },
            HostFunction("env", "xRollback", listOf(i32), emptyList<ValType>()) { _, a ->
                cb.rollback(a[0].toInt()).rollback(); null
            },
            HostFunction("env", "xUpdate", listOf(i32, i32, i32, i32, i64), emptyList<ValType>()) { i, a ->
                val m = i.memory()
                cb.update(a[0].toInt())
                    .update(a[1].toInt(), m.readCString(a[2].toInt()), m.readCString(a[3].toInt()), a[4])
                null
            },
        )

        // 공유 WASI 1개 + RW-락 정책 (§9.1 — StatelessBulkMemory 가 선행 조건):
        //  - writeLock: fd table 구조 변형 3종 — path_open(allocate)/fd_close(free)/fd_renumber(free+set)
        //  - readLock: 데이터플레인 fd_*/path_*/sock_*(table 은 get 만) + poll_oneoff(sleep 이 I/O 비차단)
        //  - 무락: clock/random/environ/args/sched_yield — 결과 쓰기도 stateless 벌크라 안전
        val lockedWasi = wasi.toHostFunctions().map { old ->
            val name = old.name()
            when {
                name == "path_open" || name == "fd_close" || name == "fd_renumber" ->
                    HostFunction(old.module(), old.name(), old.functionType()) { a, b ->
                        wasiLock.write { old.handle().apply(a, *b) }
                    }
                name == "poll_oneoff" || name.startsWith("fd_") ||
                    name.startsWith("path_") || name.startsWith("sock_") ->
                    HostFunction(old.module(), old.name(), old.functionType()) { a, b ->
                        wasiLock.read { old.handle().apply(a, *b) }
                    }
                else -> old
            }
        }.toTypedArray()

        return ImportValues.builder()
            .addFunction(*lockedWasi)
            .addFunction(threadSpawn)
            .addFunction(*vfsImports)
            .addFunction(*callbackImports)
            .addMemory(ImportMemory("env", "memory", mem))
            .build()
    }

    /**
     * **순수 JVM pthread 부트스트랩** (BUILD.md §9, `JvmPthreadBootstrapSpikeTest`) —
     * exported `pthread_create` 를 직접 호출해 libc 가 startArg(스택/TLS/`struct pthread`)를 정석으로
     * 만들게 하고, child 테이블 slot 0 에 심어둔 디스패처가 [body] 를 **정식 wasm pthread 본체로** 실행한다.
     * C 워커/재링크 불필요. body 는 자기 child 인스턴스의 exports 를 받아 SQL 을 실행하며,
     * 대기/채널/직렬화는 평범한 j.u.c 코드로 쓰면 된다.
     *
     * 주의: **동시 spawn 금지** — 호출측은 순차 spawn + warmup 으로 open/WAL/grow 초기화 경합을 피할 것 (§9).
     *
     * @return pthread 핸들 — [joinPthread] 로 종료 대기 (body 리턴 시 libc thread-exit).
     */
    /**
     * main 인스턴스 export 동시 호출 방지 — 인스턴스는 `__stack_pointer` 가 하나라 동시 진입 시 스택이
     * 충돌한다. 셋업(openRuntime)은 단일 스레드라 무관하고, 부하 중 main 을 만지는 경로(spawn/interrupt)만
     * 이 락으로 직렬화한다 (§9.2 — pthread_join 은 모델에서 소멸).
     */
    private val mainLock = java.util.concurrent.locks.ReentrantLock()

    /**
     * **park-carrier 워커** — 잠든 wasm pthread 가 정체성(스택/TLS/tid)을 보유하고, 실제 export
     * 호출은 caller 스레드가 [exports] 로 **직접** 한다 (큐 마샬링 제거 — 제안: 사용자, §9.2).
     *
     * 핸드셰이크는 future 3개 (제안: 사용자 — latch/Thread/unpark 배관 대체):
     *  - started: route B 도달(TLS/SP 세팅 완료) — **이 완료 전 [exports] 호출 금지** (eager 호출 금지 불변식).
     *  - [shutdown]: 종료 신호 — Phase2 가 이걸 join 하며 잠든다 (CF 는 가짜 완료가 없어 spurious 면역).
     *  - [lifetime]: VT 전체 수명 (완료 = wasi_thread_start 완전 리턴 = libc detached exit 완료).
     *    **워커 VT 에서 죽은 예외의 전파 채널이기도 하다** ([closeChild] 의 get 으로 표면화).
     *
     * 호출은 child 당 직렬화 필수 (__stack_pointer 전역 공유) — [SqliteWorker] 의 per-child 락이 담당.
     */
    class ChildModule internal constructor(
        val exports: JvmVfsModule_ModuleExports,
        internal val shutdown: java.util.concurrent.CompletableFuture<Unit>,
        internal val lifetime: java.util.concurrent.CompletableFuture<Void>,
    )

    /**
     * 워커 1개 spawn — route A(이 스레드, pthread_create 안)에서 child 를 **즉시** 생성하고,
     * VT 는 wasi_thread_start → Phase2(detach + shutdown.join)로 잠든다. 실패는 동기 전파
     * (route A 는 무예외 — 음수 반환으로 pthread_create 가 EAGAIN; route B 실패는 started 로).
     */
    fun spawnPthread(): ChildModule {
        val req = SpawnWorkerHandle.Phase1()
        mainLock.withLock {
            val pt = malloc(4)
            var rc = 0
            // route A 는 이 pthread_create 호출 **안에서 이 스레드에 동기로** 일어난다 — ScopedValue
            // 바인딩이 정확히 route A 에게만 보인다.
            ScopedValue.where(SpawnWorkerHandle.childCallbackScope, req).run {
                rc = exports.pthreadCreate(pt, 0, /*start_func=테이블 slot*/ 0, SpawnWorkerHandle.ARG_SENTINEL)
            }
            free(pt)
            check(rc == 0) { "pthread_create rc=$rc (route A 실패 포함); workerErrors=$workerErrors" }
        }
        // route A 동기 완료 → exports 즉시 가용 (단 사용은 started 완료 후에만 — eager 호출 금지)
        val childExports = checkNotNull(req.exports) { "route A 미수행 (fn=0 spawn 인데 Phase1 미호출)" }
        req.started.get(30, java.util.concurrent.TimeUnit.SECONDS)   // route B 실패도 여기로 전파
        return ChildModule(childExports, req.shutdown, checkNotNull(req.lifetime))
    }

    /**
     * 워커 종료: [ChildModule.shutdown] 완성 → [ChildModule.lifetime] 대기 (= wasi_thread_start 완전
     * 리턴 = libc detached exit 의 스택/TLS 자가 해제까지 완료 — pthread_join 동등 보장, main 점유 없음).
     * 워커 VT 에서 죽은 예외가 있으면 여기서 표면화된다. 호출측은 in-flight export 호출이 없음을
     * 보장할 것 ([SqliteWorker] 가 락으로 담당).
     */
    fun closeChild(c: ChildModule) {
        c.shutdown.complete(Unit)
        c.lifetime.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    /**
     * 실행 중 문장 중단 — `sqlite3_interrupt` 는 "다른 스레드에서 호출해도 안전"이 문서화된 API
     * (db 구조체에 플래그만 씀). 실행 중인 문장은 SQLITE_INTERRUPT(9)로 중단되고, 문장이 없으면 no-op.
     */
    fun interruptDb(dbPtr: Int): Unit = mainLock.withLock {
        exports.sqlite3Interrupt(dbPtr)
    }

    fun malloc(n: Int): Int = exports.malloc(n)
    fun free(ptr: Int) { if (ptr != 0) exports.free(ptr) }

    fun cString(s: String): Int {
        val b = s.toByteArray(Charsets.UTF_8)
        val p = malloc(b.size + 1)
        mem.write(p, b); mem.writeByte(p + b.size, 0)
        return p
    }

    // ---- 메인 인스턴스에서 직접 쓰는 간이 C API (셋업/검증용) ----
    fun openDb(path: String): Int {
        val pp = malloc(4)
        val rc = exports.sqlite3Open(cString(path), pp)
        val db = mem.readInt(pp)
        free(pp)
        if (rc != 0) throw SqliteNativeException(rc, "sqlite3_open($path) rc=$rc")
        return db
    }

    fun exec(db: Int, sql: String): Int = exports.sqlite3Exec(db, cString(sql), 0, 0, 0)
    fun closeDb(db: Int) { exports.sqlite3CloseV2(db) }

    private fun <T> scalar(db: Int, sql: String, read: (Int) -> T, empty: T): T {
        val pp = malloc(4)
        exports.sqlite3PrepareV2(db, cString(sql), -1, pp, 0)
        val st = mem.readInt(pp)
        val v = if (exports.sqlite3Step(st) == 100) read(st) else empty
        exports.sqlite3Finalize(st); free(pp)
        return v
    }

    fun scalarLong(db: Int, sql: String): Long = scalar(db, sql, { exports.sqlite3ColumnInt64(it, 0) }, 0L)
    fun scalarText(db: Int, sql: String): String =
        scalar(db, sql, { mem.readCString(exports.sqlite3ColumnText(it, 0)) }, "")

    override fun close() {
        wasiLock.write { wasi.close() }   // in-flight I/O 전부와 배타
    }

    companion object {
        private const val INITIAL_PAGES = 512
        private const val MAX_PAGES = 32768
        private const val ARENA_BYTES = 8 * 1024 * 1024

        /** 생성자 별칭 (기존 호출부 호환). */
        fun open(preopens: Map<String, Path> = emptyMap()) = JvmVfsRuntime(preopens)
    }
}

/**
 * 하이브리드 thread-spawn 디스패처 — main 의 `wasi.thread-spawn` import 와 child 테이블 slot 0 ref 가
 * 모두 여기를 가리킨다 (테이블은 LLD min=max 고정이라 grow 불가 → null-trap 예약석 slot 0 재활용).
 *
 * **본체 전달 = ScopedValue 2단** (과거 workerBodies 맵 + 증가 키 + 값 공간 분리 논증의 대체):
 *  1) [JvmVfsRuntime.spawnPthread] 가 [PENDING_BODY] 를 바인딩한 채 `pthread_create` 호출 —
 *     route A(libc 발 thread-spawn)는 그 export 호출 **안에서 같은 스레드에 동기로** 일어나므로
 *     바인딩이 정확히 route A 에게만 보인다. start_arg 는 [ARG_SENTINEL] 상수 (키 불필요).
 *  2) route A 가 body 를 runnable 에 평범한 클로저로 캡처 → 새 VT 에서 [WORKER_BODY] 로 **one-shot**
 *     (AtomicReference) 바인딩 → route B(`wasi_thread_start` 의 call_indirect(slot 0))가
 *     getAndSet(null) 로 소비. one-shot 인 이유: sorter 의 pthread_create 는 본체 실행 중
 *     = WORKER_BODY 스코프 **안**에서 이 디스패처로 들어오므로, "바인딩됨" 만으론 route B 와
 *     구분이 안 된다 — "이 VT 의 첫 진입만 본체"가 정확한 시맨틱이다.
 *
 * "JVM 발 위조 spawn vs guest 발 진짜 spawn(sorter 등)"은 **start_args 의 start_func 필드**로 구분 —
 * fn==0 을 위조하는 건 spawnPthread 뿐이고, 정상 guest 코드는 null 함수 포인터(slot 0 = LLD null-trap
 * 예약석)로 pthread_create 할 수 없다 (값 공간 분리, 휴리스틱 아님).
 */
class SpawnWorkerHandle(
    val delegatedSpawns: AtomicInteger,
    val spawnFuncIdx: Int,
    workerErrors: Thread.UncaughtExceptionHandler?,
) : WasmFunctionHandle {

    /** wasi_thread_start 에 넘길 tid — 고유 양수면 충분 (tls+40 에 저장됨). */
    private val threadCounter = AtomicInteger(1)

    /**
     * spawn 상태기계 — [JvmVfsRuntime.spawnPthread] 발 위조 spawn 만 이 스코프를 가진다.
     *  - [Phase1]: route A (caller 스레드, pthread_create 안) — child **eager 생성** 후 exports 회신.
     *  - [Phase2]: route B (워커 VT, call_indirect slot 0) — detach + park 루프 (정체성 캐리어).
     * sorter 등 guest 발 진짜 spawn 은 스코프 미바인딩 (Phase2 VT 는 park 중이라 spawn 못 하고,
     * caller 스레드의 실행 중 sqlite 가 부르는 경우는 바인딩이 없다) — 상태기계가 구분을 끝낸다.
     */
    /**
     * spawn 핸드셰이크 캐리어 (제안: 사용자 — future 기반):
     *  - [Phase1]: route A 가 [exports]/[lifetime] 을 채우는 요청-응답 객체 (route A 는 caller 와
     *    같은 스레드라 동기 가시성), route B 가 [started] 를 완성.
     *  - [Phase2]: route B 의 잠들기 신호쌍 — started 완성 후 [shutdown] 을 join (spurious 면역).
     * sorter 등 guest 발 진짜 spawn 은 스코프 미바인딩 (Phase2 VT 는 join 으로 잠들어 spawn 못 하고,
     * caller 스레드의 실행 중 sqlite 가 부르는 경우는 바인딩이 없다) — 상태기계가 구분을 끝낸다.
     */
    sealed interface SpawnPhase

    class Phase1 : SpawnPhase {
        @Volatile var exports: JvmVfsModule_ModuleExports? = null
        @Volatile var lifetime: java.util.concurrent.CompletableFuture<Void>? = null
        val started = java.util.concurrent.CompletableFuture<Unit>()
        val shutdown = java.util.concurrent.CompletableFuture<Unit>()
    }

    class Phase2(
        val started: java.util.concurrent.CompletableFuture<Unit>,
        val shutdown: java.util.concurrent.CompletableFuture<Unit>,
    ) : SpawnPhase

    companion object {
        internal val childCallbackScope: ScopedValue<SpawnPhase> = ScopedValue.newInstance()
        private val threadCallback = ScopedValue.newInstance<LongConsumer>()

        /** 위조 spawn 의 start_arg 상수 — start_args@12 레이아웃 자가 검증용 (키 역할 아님). */
        internal const val ARG_SENTINEL = 0x5EA1
    }

    private val factory = Thread.ofVirtual().name("sqlite-worker-", 1)
        .uncaughtExceptionHandler(workerErrors).inheritInheritableThreadLocals(false)
        .factory()

//    private val proxiedFactory = ThreadFactory {
//        factory.newThread(it)
//    }


    private val executor = Executors.newThreadPerTaskExecutor(factory)

    /** child 인스턴스 생성 + 트램펄린 패치 — route A(eager)와 진짜 spawn(lazy) 공용. */
    private fun buildChild(instance: Instance): Instance {
        // 통합 핸들이라 parent 의 ("wasi","thread-spawn") import 가 이미 이 디스패처 —
        // 이름 매칭으로 child 에도 그대로 바인딩된다. (imports().functions() 는 **추가 순서**
        // 배열 — 모듈 import 인덱스 spawnFuncIdx 로 인덱싱/교체 금지: 다른 함수를 건드린다)
        val wimports = ImportValues.builder()
            .addFunction(*instance.imports().functions())
            .addMemory(ImportMemory("env", "memory", instance.memory()))
            .build()
        val w = Instance.builder(instance.module())
            .withMachineFactory(JvmVfsModule::create)
            .withImportValues(wimports)
            .withStart(false).withInitialize(true)
            .build()
        // 부트스트랩 트램펄린: child 테이블 slot 0(LLD null-trap 예약석) → 이 디스패처.
        // spawnPthread 가 위조한 fn=0 의 call_indirect 만 여기를 지난다 (진짜 pthread_create
        // 는 fn≠0 이라 무관). setRef 의 ref 는 "모듈 함수 인덱스 공간"(import 섹션 순서 —
        // spawnFuncIdx 가 그 안에서 thread-spawn import 의 번호)이고, imports().functions()
        // 의 추가 순서 배열과는 다른 공간이다. 테이블은 인스턴스별 사본이라 main 은 불변.
        w.table(0).setRef(0, spawnFuncIdx, w)
        return w
    }

    override fun apply(instance: Instance, vararg args: Long): LongArray {
        val v = args[0].toInt()
        ThreadLocalRandom.current()   // 이 스레드의 TLR 시드 초기화 (wasi random_get 이 TLR 공유 사용)

        if (!childCallbackScope.isBound) {
            // guest 발 진짜 pthread_create (예: sorter) — lazy 생성 (sqlite 의 create 는 빨리 리턴)
            this.delegatedSpawns.incrementAndGet()
            val tid = threadCounter.getAndIncrement()
            executor.execute {
                ThreadLocalRandom.current()
                JvmVfsModule_ModuleExports(buildChild(instance)).wasiThreadStart(tid, v)
            }
            return longArrayOf(tid.toLong())
        }

        when (val phase = childCallbackScope.get()) {
            is Phase1 -> {
                // route A — main 의 pthread_create **안** (libc 가 __tl_lock 보유 구간):
                // **여기서 Java 예외가 wasm 을 관통하면 락이 고아가 된다** → 무예외, 실패 = 음수 반환
                // (wasi thread-spawn 규약: 음수 → pthread_create 가 EAGAIN 으로 깨끗하게 실패).
                return try {
                    val sMem = instance.memory()
                    // 위조 spawn 의 start_args 자가 검증 (fn==0 + SENTINEL — 레이아웃 회귀 감시, §9.1)
                    check(sMem.readInt(v + 8) == 0 && sMem.readInt(v + 12) == ARG_SENTINEL) {
                        "start_args 레이아웃 불일치 — wasi-libc 변경?"
                    }
                    val tid = threadCounter.getAndIncrement()
                    // child **eager 생성** (이 스레드 — 인스턴스 생성은 스레드 무관: 자기 전용
                    // globals/table 초기화뿐, 공유메모리의 passive segment 적용은 1회 가드됨)
                    val w = buildChild(instance)
                    val childExports = JvmVfsModule_ModuleExports(w)
                    val carrier = ScopedValue.where(childCallbackScope, Phase2(phase.started, phase.shutdown))
                    // VT 수명 전체를 future 로 — 완료 = guest exit 까지 끝, 예외 = 워커 VT 사망의 전파 채널
                    val lifetime = java.util.concurrent.CompletableFuture.runAsync({
                        carrier.run {
                            ThreadLocalRandom.current()
                            // 스택/TLS/tid 는 wasi_thread_start 가 startArg 로부터 세팅 (_initialize 안 함)
                            childExports.wasiThreadStart(tid, v)
                        }
                    }, executor)
                    // route B 도달 전에 죽으면 started 대기자(spawnPthread)에게도 실패를 전달
                    lifetime.whenComplete { _, e ->
                        if (e != null) phase.started.completeExceptionally(e)
                    }
                    phase.lifetime = lifetime
                    // eager 생성 OK / eager 호출 금지 — 받는 쪽은 started 완료 후에만 exports 사용
                    phase.exports = childExports
                    longArrayOf(tid.toLong())
                } catch (t: Throwable) {
                    Thread.currentThread().uncaughtExceptionHandler?.uncaughtException(Thread.currentThread(), t)
                    longArrayOf(-1)
                }
            }
            is Phase2 -> {
                // route B — 워커 VT, wasi_thread_start 의 call_indirect(slot 0). TLS/SP 세팅 완료 상태.
                // 여기서 리턴하면 libc thread-exit (detached 자원 자가 해제) — 그 전까지 잠든 캐리어.
                val x = JvmVfsModule_ModuleExports(instance)
                check(x.pthreadDetach(x.pthreadSelf()) == 0) { "pthread_detach 실패" }
                phase.started.complete(Unit)
                // CF join = spurious 면역 잠들기 (가짜 완료 없음 — park 루프/Thread/unpark 배관 불필요)
                phase.shutdown.join()
                return longArrayOf(0)
            }
        }
    }
}
