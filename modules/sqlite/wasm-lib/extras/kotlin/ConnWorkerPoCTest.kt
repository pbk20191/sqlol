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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M3 다음 스텝: 커넥션-워커 메시지패싱 PoC.
 * wasm 안의 워커 스레드(pthread)가 sqlite3 커넥션을 소유하고, JVM 이 공유메모리 채널로
 * SQL 을 제출하면 실행해 결과를 돌려준다. JVM↔워커는 atomic wait/notify 로 동기화.
 *
 * channel: state@0(i32), rc@4(i32), result@8(i64), sql@16(cstr)
 */
class ConnWorkerPoCTest {

    private val STATE = 0
    private val RC = 4
    private val RESULT = 8
    private val SQL = 16

    @Test
    fun `워커 스레드가 JVM 이 제출한 SQL 을 실행`() {
        val mod = javaClass.getResourceAsStream("/sqlite3-threads.wasm").use { Parser.parse(it) }
        val sharedMem = ByteBufferMemory(MemoryLimits(512, 32768, true))
        val wasi = WasiPreview1.builder()
            .withOptions(WasiOptions.builder().withStdout(System.out).withStderr(System.err).build())
            .build()
        val workerErrors = ConcurrentLinkedQueue<Throwable>()

        // 정석 thread-spawn (tid = JVM threadId)
        val threadSpawn = HostFunction(
            "wasi", "thread-spawn", listOf(ValType.I32), listOf(ValType.I32),
        ) { parent: Instance, args: LongArray ->
            val startArg = args[0]
            val thread = Thread.ofPlatform().name("conn-worker").start {
                try {
                    val w = Instance.builder(parent.module())
                        .withImportValues(parent.imports())
                        .withStart(false).withInitialize(true).build()
                    w.exports().function("wasi_thread_start")
                        .apply(Thread.currentThread().threadId(), startArg)
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

        val ch = main.export("malloc").apply(4096L)[0].toInt()
        val rc0 = main.export("start_worker").apply(ch.toLong())[0].toInt()
        assertEquals(0, rc0, "start_worker(pthread_create) 성공해야 함")

        // SQL 제출: ch->sql 에 쓰고, wasm 명령으로 요청 게시(ch_request) + 결과 대기(ch_await).
        // atomic/notify/wait 가 전부 wasm 측이라 워커의 wait32 와 정상 연동된다.
        fun submit(sql: String): Pair<Int, Long> {
            sharedMem.writeCString(ch + SQL, sql)
            main.export("ch_request").apply(ch.toLong())
            main.export("ch_await").apply(ch.toLong())     // 워커가 done 만들 때까지 wasm 에서 블록
            return sharedMem.readInt(ch + RC) to sharedMem.readLong(ch + RESULT)
        }

        // 무한 대기 방지: 명령 시퀀스를 스레드에서 돌리고 타임아웃 join
        val results = HashMap<String, Pair<Int, Long>>()
        val driver = Thread {
            try {
                results["create"] = submit("CREATE TABLE t(x)")
                results["ins1"] = submit("INSERT INTO t VALUES (1),(2),(3)")
                results["cnt1"] = submit("SELECT count(*) FROM t")
                submit("INSERT INTO t VALUES (4),(5)")
                results["cnt2"] = submit("SELECT count(*) FROM t")
                results["pow"] = submit("SELECT CAST(pow(2,10) AS INTEGER)")
            } catch (t: Throwable) {
                workerErrors += t
            } finally {
                main.export("ch_stop").apply(ch.toLong())
            }
        }
        driver.start()
        driver.join(20_000)
        assertTrue(!driver.isAlive, "PoC 가 20초 내 끝나지 않음 (futex 연동 실패)")

        assertTrue(workerErrors.isEmpty(), "오류: ${workerErrors.toList()}")
        assertEquals(0, results["create"]!!.first)
        assertEquals(3L, results["cnt1"]!!.second, "3행")
        assertEquals(5L, results["cnt2"]!!.second, "5행")
        assertEquals(1024L, results["pow"]!!.second, "pow(2,10)")
    }
}
