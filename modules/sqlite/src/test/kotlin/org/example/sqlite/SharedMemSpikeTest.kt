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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M3 스파이크 2: 하나의 공유 Memory 를 **두 인스턴스**가 공유. 각 인스턴스는 자기 스택/TLS 를
 * 가져야 한다(안 그러면 충돌). 우선 순차 사용으로 스택/TLS 설정의 정확성만 검증한다.
 */
class SharedMemSpikeTest {

    private val STACK_PER_INSTANCE = 4 * 1024 * 1024  // 4MB

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

    /** :memory: db 에 표 만들고 N행 넣고 카운트 반환 */
    private fun exercise(inst: Instance, rows: Int): Long {
        val ppDb = call(inst, "malloc", 4).toInt()
        check(call(inst, "sqlite3_open", cStr(inst, ":memory:").toLong(), ppDb.toLong()).toInt() == 0)
        val db = inst.memory().readInt(ppDb).toLong()
        check(call(inst, "sqlite3_exec", db, cStr(inst, "CREATE TABLE t(x)").toLong(), 0, 0, 0).toInt() == 0)
        for (i in 1..rows) {
            call(inst, "sqlite3_exec", db, cStr(inst, "INSERT INTO t VALUES ($i)").toLong(), 0, 0, 0)
        }
        // count
        val ppStmt = call(inst, "malloc", 4).toInt()
        call(inst, "sqlite3_prepare_v2", db, cStr(inst, "SELECT count(*) FROM t").toLong(), -1, ppStmt.toLong(), 0)
        val stmt = inst.memory().readInt(ppStmt).toLong()
        call(inst, "sqlite3_step", stmt)
        val count = call(inst, "sqlite3_column_int64", stmt, 0)
        call(inst, "sqlite3_finalize", stmt)
        call(inst, "sqlite3_close_v2", db)
        return count
    }

    @Test
    fun `두 인스턴스 공유메모리 - 순차 사용`() {
        val sharedMem = ByteBufferMemory(MemoryLimits(512, 32768, true))
        val mod = module()

        // 인스턴스 A: 메인 (전체 초기화)
        val a = Instance.builder(mod).withImportValues(imports(sharedMem))
            .withStart(false).withInitialize(true).build()
        call(a, "_initialize")
        val spA = a.global(0).value
        println("[M3-spike2] A __stack_pointer init = $spA")

        // 인스턴스 B: A 의 heap 에서 스택/TLS 를 할당받아 자기 것으로 설정 (메모리/ctor 재초기화 안 함)
        val b = Instance.builder(mod).withImportValues(imports(sharedMem))
            .withStart(false).withInitialize(true).build()
        val stackBase = call(a, "malloc", STACK_PER_INSTANCE.toLong()).toInt()
        val stackTop = (stackBase + STACK_PER_INSTANCE) and 0xF.inv()
        b.global(0).value = stackTop.toLong()                 // __stack_pointer
        val tlsBase = call(a, "malloc", 4096).toInt()
        call(b, "__wasm_init_tls", tlsBase.toLong())
        println("[M3-spike2] B stackTop=$stackTop tlsBase=$tlsBase")

        // 순차로 각자 자기 :memory: db 사용
        val ca = exercise(a, 100)
        val cb = exercise(b, 250)
        println("[M3-spike2] A count=$ca, B count=$cb")
        assertEquals(100L, ca)
        assertEquals(250L, cb)

        // 교차로 다시 한 번 (상호 간섭 없는지)
        assertEquals(50L, exercise(a, 50))
        assertEquals(70L, exercise(b, 70))
    }
}
