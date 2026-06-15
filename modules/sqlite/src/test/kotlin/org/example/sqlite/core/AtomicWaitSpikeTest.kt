package org.example.sqlite.core

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
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M3 격리 진단: 공유 메모리 위 두 인스턴스가 **순수 malloc/free 만** 동시 반복.
 * 데드락이 여기서도 나면 원인은 Chicory 의 atomic wait/notify (futex) — SQLite 무관.
 */
class AtomicWaitSpikeTest {

    private fun module(): WasmModule =
        javaClass.getResourceAsStream("/sqlite3-threads.wasm").use { Parser.parse(it) }

    private fun imports(m: ByteBufferMemory): ImportValues {
        val wasi = WasiPreview1.builder()
            .withOptions(WasiOptions.builder().withStdout(System.out).withStderr(System.err).build()).build()
        val ts = HostFunction("wasi", "thread-spawn", listOf(ValType.I32), listOf(ValType.I32)) {
            _: Instance, _: LongArray -> longArrayOf(-1L)
        }
        return ImportValues.builder().addFunction(*wasi.toHostFunctions()).addFunction(ts)
            .addMemory(ImportMemory("env", "memory", m)).build()
    }

    private fun call(i: Instance, n: String, vararg a: Long): Long =
        i.export(n).apply(*a).let { if (it == null || it.isEmpty()) 0L else it[0] }

    @Test
    fun `공유메모리 동시 malloc-free 경합`() {
        val mem = ByteBufferMemory(MemoryLimits(512, 32768, true))
        val mod = module()
        val a = Instance.builder(mod).withImportValues(imports(mem)).withStart(false).withInitialize(true).build()
        call(a, "_initialize")
        val b = Instance.builder(mod).withImportValues(imports(mem)).withStart(false).withInitialize(true).build()
        val stackBase = call(a, "malloc", (8 * 1024 * 1024).toLong()).toInt()
        b.global(0).value = ((stackBase + 8 * 1024 * 1024) and 0xF.inv()).toLong()
        call(b, "__wasm_init_tls", call(a, "malloc", 4096).toLong())

        val errors = ConcurrentLinkedQueue<Throwable>()
        val barrier = CyclicBarrier(2)
        fun worker(tag: String, inst: Instance) = Thread {
            try {
                barrier.await()
                for (k in 0 until 2000) {
                    val p = call(inst, "malloc", 64).toInt()
                    if (p != 0) call(inst, "free", p.toLong())
                    if (k % 500 == 0) println("[M3-aw] $tag k=$k")
                }
                println("[M3-aw] $tag 완료")
            } catch (e: Throwable) { errors += e }
        }
        val tA = worker("A", a); val tB = worker("B", b)
        tA.start(); tB.start()
        tA.join(30_000); tB.join(30_000)
        if (tA.isAlive || tB.isAlive) error("malloc/free 동시 경합 30초 내 미완료 → Chicory atomic wait/notify 데드락")
        assertTrue(errors.isEmpty(), "오류: ${errors.toList()}")
    }
}
