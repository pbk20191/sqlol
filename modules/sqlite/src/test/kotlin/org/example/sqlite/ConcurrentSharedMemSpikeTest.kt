package org.example.sqlite

import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportMemory
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.dylibso.chicory.wasm.types.ValType
import com.dylibso.chicory.wasi.WasiOptions
import com.dylibso.chicory.wasi.WasiPreview1
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M3 결정적 게이트: 하나의 공유 Memory 위에서 두 인스턴스를 **서로 다른 JVM 스레드로 동시에** 구동.
 * THREADSAFE=1 mutex + thread-safe malloc + Chicory 의 atomic wait/notify 가 실제 경합을 견디는지 검증.
 * (각 스레드는 자기 :memory: db 사용 — shm VFS 이전 단계의 동시성 안전성 확인)
 */
class ConcurrentSharedMemSpikeTest {

    private val STACK = 8 * 1024 * 1024

    private fun module(): WasmModule =
        javaClass.getResourceAsStream("/sqlite3-threads.wasm").use { Parser.parse(it) }

    private fun imports(sharedMem: ByteBufferMemory): ImportValues {
        val wasi = WasiPreview1.builder()
            .withOptions(WasiOptions.builder().withStdout(System.out).withStderr(System.err).build())
            .build()
        val threadSpawn = HostFunction("wasi", "thread-spawn", listOf(ValType.I32), listOf(ValType.I32)) {
            _: Instance, _: LongArray -> longArrayOf(-1L)
        }
        return ImportValues.builder()
            .addFunction(*wasi.toHostFunctions())
            .addFunction(threadSpawn)
            .addMemory(ImportMemory("env", "memory", sharedMem))
            .build()
    }

    private fun call(inst: Instance, name: String, vararg a: Long): Long {
        val r = inst.export(name).apply(*a)
        return if (r == null || r.isEmpty()) 0L else r[0]
    }

    private fun cStr(inst: Instance, s: String): Int {
        val b = s.toByteArray(Charsets.UTF_8)
        val p = call(inst, "malloc", (b.size + 1).toLong()).toInt()
        inst.memory().write(p, b); inst.memory().write(p + b.size, byteArrayOf(0))
        return p
    }

    private fun freeP(inst: Instance, p: Int) { if (p != 0) call(inst, "free", p.toLong()) }

    /** :memory: db 생성 → rows 삽입 → count 확인 → 닫기. malloc/free 를 대량 발생시켜 힙 경합 유발. */
    private fun exercise(inst: Instance, rows: Int): Long {
        val ppDb = call(inst, "malloc", 4).toInt()
        val name = cStr(inst, ":memory:")
        check(call(inst, "sqlite3_open", name.toLong(), ppDb.toLong()).toInt() == 0)
        val db = inst.memory().readInt(ppDb).toLong()
        freeP(inst, name); freeP(inst, ppDb)
        run {
            val sql = cStr(inst, "CREATE TABLE t(x INTEGER, s TEXT)")
            check(call(inst, "sqlite3_exec", db, sql.toLong(), 0, 0, 0).toInt() == 0)
            freeP(inst, sql)
        }
        for (i in 1..rows) {
            val sql = cStr(inst, "INSERT INTO t VALUES ($i, 'row-$i-payload')")
            call(inst, "sqlite3_exec", db, sql.toLong(), 0, 0, 0)
            freeP(inst, sql)
        }
        val ppStmt = call(inst, "malloc", 4).toInt()
        val q = cStr(inst, "SELECT count(*) FROM t")
        call(inst, "sqlite3_prepare_v2", db, q.toLong(), -1, ppStmt.toLong(), 0)
        val stmt = inst.memory().readInt(ppStmt).toLong()
        call(inst, "sqlite3_step", stmt)
        val count = call(inst, "sqlite3_column_int64", stmt, 0)
        call(inst, "sqlite3_finalize", stmt)
        freeP(inst, q); freeP(inst, ppStmt)
        call(inst, "sqlite3_close_v2", db)
        return count
    }

    /** pthread struct(tid) 는 __tls_base+20, tid 필드는 그 +20 = __tls_base+40 (wasi_thread_start_C 디스어셈블로 확인) */
    private fun assignTid(inst: Instance, tid: Int) {
        val tlsBase = inst.global(1).value.toInt()   // global[1] = __tls_base
        inst.memory().writeI32(tlsBase + 40, tid)
    }

    private fun makeWorker(mod: WasmModule, sharedMem: ByteBufferMemory, main: Instance, tid: Int): Instance {
        val w = Instance.builder(mod).withImportValues(imports(sharedMem))
            .withStart(false).withInitialize(true).build()
        val stackBase = call(main, "malloc", STACK.toLong()).toInt()
        val stackTop = (stackBase + STACK) and 0xF.inv()
        w.global(0).value = stackTop.toLong()
        val tls = call(main, "malloc", 4096).toInt()
        call(w, "__wasm_init_tls", tls.toLong())
        call(w, "__wasi_init_tp")   // 이 인스턴스의 TLS 기반 pthread self 초기화
        assignTid(w, tid)            // 고유 tid (pthread mutex lock-word 소유자 식별용)
        return w
    }

    @Disabled(
        "수동 JVM 부트스트랩(스택/TLS/tid 를 global 직접 설정)의 동시 실행은 여전히 ~33% 플래키 " +
            "(2026-06 재측정 8/12: 데드락 3 + count mismatch 1). fd-table/wakeup 수정과 무관 — 원인은 " +
            "wasi_thread_start(pthread_create) 없이 수동 부트스트랩 시 SQLite pthread mutex/TLS 가 어긋나는 것. " +
            "견고한 다중 커넥션은 wasm pthread 부트스트랩(extras/conn_worker) 경로를 써야 함. BUILD.md §M3.",
    )
    @Test
    fun `두 인스턴스 공유메모리 - 동시 실행 경합`() {
        val sharedMem = ByteBufferMemory(MemoryLimits(512, 32768, true))
        val mod = module()

        val a = Instance.builder(mod).withImportValues(imports(sharedMem))
            .withStart(false).withInitialize(true).build()
        call(a, "_initialize")
        assignTid(a, 1)                 // 메인도 고유 tid (0 은 mutex 에서 unlocked 의미라 불가)
        call(a, "sqlite3_initialize")   // 전역 초기화를 워커 시작 전 1회 선행 (동시 init 경합 회피)
        val b = makeWorker(mod, sharedMem, a, tid = 2)

        // 진단: 각 인스턴스의 tid(struct = __tls_base+20, tid 필드 +20) 확인
        val tidA = a.memory().readInt(a.global(1).value.toInt() + 40)
        val tidB = b.memory().readInt(b.global(1).value.toInt() + 40)
        val selfA = a.memory().readInt(a.global(1).value.toInt() + 20)
        val selfB = b.memory().readInt(b.global(1).value.toInt() + 20)
        println("[M3-spike3] A tlsBase=${a.global(1).value} tid=$tidA self=$selfA")
        println("[M3-spike3] B tlsBase=${b.global(1).value} tid=$tidB self=$selfB")

        val iterations = 30
        val errors = ConcurrentLinkedQueue<Throwable>()
        val barrier = CyclicBarrier(2)

        fun worker(tag: String, inst: Instance, rows: Int) = Thread {
            try {
                barrier.await()
                repeat(iterations) { k ->
                    val c = exercise(inst, rows)
                    if (c != rows.toLong()) error("count mismatch: got $c expected $rows")
                }
            } catch (e: Throwable) {
                errors += e
            }
        }

        val tA = worker("A", a, 120)
        val tB = worker("B", b, 200)
        tA.start(); tB.start()
        tA.join(30_000); tB.join(30_000)
        if (tA.isAlive || tB.isAlive) error("30초 내 미완료 (데드락 의심: atomic wait/notify)")

        println("[M3-spike3] 동시 실행 완료. errors=${errors.size}")
        errors.forEach { println("[M3-spike3] ERROR: $it") }
        assertTrue(errors.isEmpty(), "동시 실행 중 오류: ${errors.toList()}")
    }
}
