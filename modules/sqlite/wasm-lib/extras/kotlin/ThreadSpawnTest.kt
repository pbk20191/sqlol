package org.example.sqlite

import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportMemory
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.dylibso.chicory.wasm.types.ValType
import com.dylibso.chicory.wasi.WasiOptions
import com.dylibso.chicory.wasi.WasiPreview1
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M3: wasi-threads 정석 배선 검증.
 * import `wasi.thread-spawn` 을 제대로 구현(새 JVM 스레드 + 공유메모리 인스턴스 + export `wasi_thread_start` 호출)하고,
 * wasm 안의 `pthread_create`(thread_shim 의 spawn_add)가 실제로 워커 스레드를 돌리는지 확인한다.
 */
class ThreadSpawnTest {

    @Test
    fun `pthread_create - wasi thread-spawn 왕복`() {
        val mod = javaClass.getResourceAsStream("/sqlite3-threads.wasm").use { Parser.parse(it) }
        val sharedMem = ByteBufferMemory(MemoryLimits(512, 32768, true))
        val wasi = WasiPreview1.builder()
            .withOptions(WasiOptions.builder().withStdout(System.out).withStderr(System.err).build())
            .build()

//        val nextTid = AtomicInteger(2)   // 메인은 1 로 가정, 워커는 2..
        val workerErrors = ConcurrentLinkedQueue<Throwable>()

        // 정석 thread-spawn 구현: 새 스레드에서 공유메모리 인스턴스를 만들고 wasi_thread_start 호출
        val threadSpawn = HostFunction(
            "wasi", "thread-spawn", listOf(ValType.I32), listOf(ValType.I32),
        ) { parent: Instance, args: LongArray ->
            val startArg = args[0]
//            val tid = nextTid.getAndIncrement()
            val thread = Thread.ofPlatform().name("wasi-thread-worker").start {
                try {
                    val worker = Instance.builder(parent.module())
                        .withImportValues(parent.imports())   // 같은 공유메모리/임포트
                        .withStart(false)
                        .withInitialize(true)                 // 테이블/글로벌만 (ctor/_initialize 호출 안 함)
                        .build()
                    // 스택/TLS/tid 는 wasi_thread_start 가 startArg 로부터 세팅
                    worker.exports().function("wasi_thread_start").apply(Thread.currentThread().threadId(), startArg)
                } catch (t: Throwable) {
                    workerErrors += t
                }
            }
            longArrayOf(thread.threadId())
        }

        val imports = ImportValues.builder()
            .addFunction(*wasi.toHostFunctions())
            .addFunction(threadSpawn)
            .addMemory(ImportMemory("env", "memory", sharedMem))
            .build()

        val main = Instance.builder(mod).withImportValues(imports)
            .withStart(false).withInitialize(true).build()
        main.export("_initialize").apply()

        // spawn_add(5): pthread_create → wasi.thread-spawn → worker → pthread_join. 워커 돌면 105.
        var result = Int.MIN_VALUE
        val caller = Thread {
            result = main.export("spawn_add").apply(5L)[0].toInt()
        }
        caller.start()
        caller.join(30_000)

        assertFalse(caller.isAlive, "spawn_add 가 30초 내 끝나지 않음 (thread-spawn/join 데드락)")
        assertTrue(workerErrors.isEmpty(), "워커 스레드 오류: ${workerErrors.toList()}")
        assertEquals(105, result, "워커가 box(=5)에 100을 더해 105를 반환해야 함")
    }
}
