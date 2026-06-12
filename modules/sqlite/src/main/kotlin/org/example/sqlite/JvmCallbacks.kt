package org.example.sqlite

import com.dylibso.chicory.runtime.Memory
import com.example.wasm.JvmVfsModule_ModuleExports
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * wasm→JVM 콜백 레지스트리 — 런타임당 1개. helpers.c 의 env import(xFunc/xCompare/…)가
 * [JvmVfsRuntime.buildImports] 의 호스트 함수로 들어오면 **user_data 의 정수 키**로 여기서 찾는다.
 *
 * 디스패치 키가 스레드가 아니라 user_data 인 이유 (§9.1): 콜레이션은 sorter 보조 스레드(손자 VT,
 * 워커 ScopedValue 스코프 밖)에서도 호출된다 — user_data 는 sqlite 가 모든 콜백에 끼워 주는
 * 스레드 무관 통로다. 키 0 은 NULL 과 겹치므로 1부터 발급.
 */
class JvmCallbacks internal constructor() {

    /**
     * UDF 훅 — 호출 스레드(그 커넥션의 워커 VT) 위에서 실행된다. [env] 의 value/result 메서드를
     * **직접**(큐 없이) 호출할 것 — 지금 그 스레드가 wasm step 안에 있으므로 재진입 export 호출이
     * 정확하고, 자기 큐에 제출하면 데드락이다.
     */
    interface Udf {
        fun xFunc(env: CallbackEnv, ctx: Int, argc: Int, argv: Int)
        fun xStep(env: CallbackEnv, ctx: Int, argc: Int, argv: Int)
        fun xFinal(env: CallbackEnv, ctx: Int)
        fun xValue(env: CallbackEnv, ctx: Int)
        fun xInverse(env: CallbackEnv, ctx: Int, argc: Int, argv: Int)
    }

    fun interface Collation {
        /** [a]/[b] 는 UTF-8 바이트 (등록 인코딩 SQLITE_UTF8). 음수/0/양수 비교 결과. */
        fun compare(a: ByteArray, b: ByteArray): Int
    }

    fun interface Busy {
        /** @return 0 = 포기(BUSY 반환), 비0 = 재시도 */
        fun callback(nPrev: Int): Int
    }

    fun interface Progress {
        /** @return 비0 = 실행 중단(INTERRUPT) */
        fun progress(): Int
    }

    fun interface Commit {
        /** @return 비0 = 커밋을 롤백으로 전환 */
        fun commit(): Int
    }

    fun interface Rollback {
        fun rollback()
    }

    fun interface Update {
        fun update(op: Int, dbName: String, table: String, rowId: Long)
    }

    private val nextKey = AtomicInteger(1)
    private val udfs = ConcurrentHashMap<Int, Udf>()
    private val collations = ConcurrentHashMap<Int, Collation>()
    private val busies = ConcurrentHashMap<Int, Busy>()
    private val progresses = ConcurrentHashMap<Int, Progress>()
    private val commits = ConcurrentHashMap<Int, Commit>()
    private val rollbacks = ConcurrentHashMap<Int, Rollback>()
    private val updates = ConcurrentHashMap<Int, Update>()

    fun register(u: Udf): Int = nextKey.getAndIncrement().also { udfs[it] = u }
    fun register(c: Collation): Int = nextKey.getAndIncrement().also { collations[it] = c }
    fun register(b: Busy): Int = nextKey.getAndIncrement().also { busies[it] = b }
    fun register(p: Progress): Int = nextKey.getAndIncrement().also { progresses[it] = p }
    fun register(c: Commit): Int = nextKey.getAndIncrement().also { commits[it] = c }
    fun register(r: Rollback): Int = nextKey.getAndIncrement().also { rollbacks[it] = r }
    fun register(u: Update): Int = nextKey.getAndIncrement().also { updates[it] = u }

    /** 훅(busy/progress/commit/rollback/update)은 destructor 가 없어 호출측이 직접 해제한다. */
    fun free(key: Int) {
        udfs.remove(key); collations.remove(key); busies.remove(key)
        progresses.remove(key); commits.remove(key); rollbacks.remove(key); updates.remove(key)
    }

    internal fun udf(key: Int): Udf = udfs[key] ?: error("미등록 UDF key=$key")
    internal fun collation(key: Int): Collation = collations[key] ?: error("미등록 콜레이션 key=$key")
    internal fun busy(key: Int): Busy = busies[key] ?: error("미등록 busy handler key=$key")
    internal fun progress(key: Int): Progress = progresses[key] ?: error("미등록 progress handler key=$key")
    internal fun commit(key: Int): Commit = commits[key] ?: error("미등록 commit hook key=$key")
    internal fun rollback(key: Int): Rollback = rollbacks[key] ?: error("미등록 rollback hook key=$key")
    internal fun update(key: Int): Update = updates[key] ?: error("미등록 update hook key=$key")
}

/**
 * 콜백 실행 컨텍스트 — **호출한 인스턴스**의 exports/mem 파사드. 콜백은 wasm step 의 한가운데서
 * 들어오므로 여기 메서드들은 재진입 export 호출이다 (C 호출 규약상 __stack_pointer 가 유효 — 중첩
 * C 호출과 동일). chicory 타입을 노출하지 않아 modules/sqlite-jdbc 가 그대로 쓸 수 있다.
 */
class CallbackEnv internal constructor(
    private val x: JvmVfsModule_ModuleExports,
    private val mem: Memory,
) {
    /** sqlite3_user_data(ctx) — 레지스트리 키. */
    fun userData(ctx: Int): Int = x.sqlite3UserData(ctx)

    /** 포인터 역참조 (예: sqlite3_value** argv 의 i 번째 → argv+4i 를 [deref]). */
    fun deref(p: Int): Int = mem.readInt(p)

    fun valueText(v: Int): String? {
        val p = x.sqlite3ValueText(v)
        return if (p == 0) null else mem.readString(p, x.sqlite3ValueBytes(v))
    }

    fun valueBlob(v: Int): ByteArray? {
        val p = x.sqlite3ValueBlob(v)
        return if (p == 0) null else mem.readBytes(p, x.sqlite3ValueBytes(v))
    }

    fun valueDouble(v: Int): Double = x.sqlite3ValueDouble(v)
    fun valueLong(v: Int): Long = x.sqlite3ValueInt64(v)
    fun valueInt(v: Int): Int = x.sqlite3ValueInt64(v).toInt()
    fun valueType(v: Int): Int = x.sqlite3ValueType(v)

    fun resultNull(ctx: Int) = x.sqlite3ResultNull(ctx)
    fun resultLong(ctx: Int, v: Long) = x.sqlite3ResultInt64(ctx, v)
    fun resultDouble(ctx: Int, v: Double) = x.sqlite3ResultDouble(ctx, v)

    fun resultText(ctx: Int, s: String?) {
        if (s == null) return resultNull(ctx)
        withScratch(s.toByteArray(Charsets.UTF_8)) { p, n -> x.sqlite3ResultText(ctx, p, n, SQLITE_TRANSIENT) }
    }

    fun resultBlob(ctx: Int, b: ByteArray?) {
        if (b == null) return resultNull(ctx)
        withScratch(b) { p, n -> x.sqlite3ResultBlob(ctx, p, n, SQLITE_TRANSIENT) }
    }

    fun resultError(ctx: Int, msg: String) {
        withScratch(msg.toByteArray(Charsets.UTF_8)) { p, n -> x.sqlite3ResultError(ctx, p, n) }
    }

    /** TRANSIENT(-1) = sqlite 가 호출 중 복사 → 즉시 free 가능. 콜백은 저빈도라 per-call malloc 허용. */
    private inline fun withScratch(b: ByteArray, f: (Int, Int) -> Unit) {
        val p = x.malloc(maxOf(b.size, 1))
        try {
            mem.write(p, b)
            f(p, b.size)
        } finally {
            x.free(p)
        }
    }

    private companion object {
        const val SQLITE_TRANSIENT = -1
    }
}
