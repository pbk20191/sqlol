package org.example.sqlite.core

import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportMemory
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.dylibso.chicory.wasm.types.ValType
import com.dylibso.chicory.wasi.WasiOptions
import com.dylibso.chicory.wasi.WasiPreview1
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M3 스파이크: wasm32-wasi-threads 로 빌드한 (공유메모리) sqlite3-threads.wasm 을
 * Chicory 인터프리터로 **단일 인스턴스** 띄우기.
 * 검증 목표: 공유 Memory 주입 + wasi.thread-spawn stub + TLS/메모리 초기화 + atomics 가
 * Chicory 에서 동작하는가 (멀티 인스턴스 공유 이전의 기반 검증).
 */
class ThreadsWasmSpikeTest {

    private fun loadModule() =
        javaClass.getResourceAsStream("/sqlite3-threads.wasm").use { Parser.parse(it) }

    @Test
    fun `단일 인스턴스 - 공유메모리 threads 빌드 기동`() {
        val module = loadModule()

        // 공유 Memory (threads 빌드는 shared memory 를 import 한다)
        val sharedMem = ByteBufferMemory(MemoryLimits(512, 32768, true))

        val wasi = WasiPreview1.builder()
            .withOptions(WasiOptions.builder().withStdout(System.out).withStderr(System.err).build())
            .build()

        // SQLite 는 스레드를 spawn 하지 않으므로 stub (호출되면 실패 반환)
        val threadSpawn = HostFunction(
            "wasi", "thread-spawn",
            listOf(ValType.I32), listOf(ValType.I32),
        ) { _: Instance, _: LongArray -> longArrayOf(-1L) }

        val imports = ImportValues.builder()
            .addFunction(*wasi.toHostFunctions())
            .addFunction(threadSpawn)
            .addMemory(ImportMemory("env", "memory", sharedMem))
            .build()

        val instance = Instance.builder(module)
            .withImportValues(imports)
            .withStart(false)
            .withInitialize(true)
            .build()

        instance.export("_initialize").apply()

        val mem: Memory = instance.memory()
        val verPtr = instance.export("sqlite3_libversion").apply()[0].toInt()
        val version = mem.readCString(verPtr)
        println("[M3-spike] threads-wasm SQLite 버전: $version")
        assertEquals("3.53.1", version)
    }
}
