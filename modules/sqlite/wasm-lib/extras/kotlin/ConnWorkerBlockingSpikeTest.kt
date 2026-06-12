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
import org.junit.jupiter.api.Disabled

/**
 * Spike B (의도적 교착 재현용, @Disabled): **단일 `state` 워드로 양방향 신호하면 교착한다**는 안티패턴 증거.
 *
 * 워커(`start_worker_blocking`)는 `wait32(state, s, -1)` 무한 대기, JVM 은 Memory.atomicWriteInt+atomicNotify
 * 로 깨우고 Memory.atomicWait 로 done 을 수신한다. 워커와 드라이버가 **같은 주소(state@0)** 에서 대기한다.
 *
 * 결과: `[create]` 직후 첫 INSERT 에서 즉시 교착(driver/worker 둘 다 waitOn, state=1).
 *   - 진짜 원인 = **wakeup 도둑질**: `pendingWakeups` 는 waiter 종류를 구분 못 하는 단일 카운터라, 드라이버가
 *     워커용 wakeup 을 가로채 소비 → 워커는 pendingWakeups==0 보고 재수면(waitOn 은 값 조건 재검사 안 함).
 *   - (주의) 가시성 문제 아님: wasm atomic.load/store 는 인터프리터에서 atomicReadInt/atomicWriteInt 로
 *     monitor 동기화되어 낮춰진다(InterpreterMachine).
 *   - 올바른 해법: 방향별 주소 분리 → ConnWorker2AddrSpikeTest (무폴링 -1 로 2000+/s 안정).
 *
 * channel: state@0(i32,_Atomic), rc@4, result@8(i64), sql@16(cstr)
 */
@Disabled("의도적 교착 재현(안티패턴): 단일 워드 양방향 신호 → wakeup 도둑질. 해법은 ConnWorker2AddrSpikeTest. 상세는 KDoc/BUILD.md §8.")
class ConnWorkerBlockingSpikeTest {

    private val STATE = 0
    private val RC = 4
    private val RESULT = 8
    private val SQL = 16

    private fun newMain(mem: ByteBufferMemory, errors: ConcurrentLinkedQueue<Throwable>): Instance {
        val mod = javaClass.getResourceAsStream("/sqlite3-threads.wasm").use { Parser.parse(it) }
        val wasi = WasiPreview1.builder()
            .withOptions(WasiOptions.builder().withStdout(System.out).withStderr(System.err).build())
            .build()
        val threadSpawn = HostFunction(
            "wasi", "thread-spawn", listOf(ValType.I32), listOf(ValType.I32),
        ) { parent: Instance, args: LongArray ->
            val startArg = args[0]
            val thread = Thread.ofPlatform().name("blk-worker").start {
                try {
                    val w = Instance.builder(parent.module())
                        .withImportValues(parent.imports())
                        .withStart(false).withInitialize(true).build()
                    w.exports().function("wasi_thread_start")
                        .apply(Thread.currentThread().threadId(), startArg)
                } catch (t: Throwable) {
                    errors += t
                }
            }
            longArrayOf(thread.threadId())
        }
        val imports = ImportValues.builder()
            .addFunction(*wasi.toHostFunctions())
            .addFunction(threadSpawn)
            .addMemory(ImportMemory("env", "memory", mem))
            .build()
        val main = Instance.builder(mod).withImportValues(imports)
            .withStart(false).withInitialize(true).build()
        main.export("_initialize").apply()
        return main
    }

    /** JVM-원자 신호로 SQL 제출 + done 블로킹 수신 (폴링 없음, 무한 대기). */
    private fun submit(mem: ByteBufferMemory, ch: Int, sql: String): Pair<Int, Long> {
        mem.writeCString(ch + SQL, sql)
        mem.atomicWriteInt(ch + STATE, 1)        // REQUEST (release)
        mem.atomicNotify(ch + STATE, 1)          // 워커의 wait32(-1) 깨우기
        var s = mem.atomicReadInt(ch + STATE)
        while (s != 2) {                         // done 까지 무한 블록
            mem.atomicWait(ch + STATE, s, -1L)
            s = mem.atomicReadInt(ch + STATE)
        }
        return mem.readInt(ch + RC) to mem.readLong(ch + RESULT)
    }

    @Test
    fun `단일 워커 - JVM원자 신호로 수천 SQL 무폴링 왕복`() {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val mem = ByteBufferMemory(MemoryLimits(512, 32768, true))
        val main = newMain(mem, errors)
        val ch = main.export("malloc").apply(4096L)[0].toInt()
        assertEquals(0, main.export("start_worker_blocking").apply(ch.toLong())[0].toInt())

        val rounds = 40
        val t0 = System.currentTimeMillis()
        val driver = Thread {
            try {
                assertEquals(0, submit(mem, ch, "CREATE TABLE t(x)").first)
                var mark = System.currentTimeMillis()
                for (r in 1..rounds) {
                    submit(mem, ch, "INSERT INTO t VALUES ($r)")
                    val (rc, cnt) = submit(mem, ch, "SELECT count(*) FROM t")
                    if (rc != 0 || cnt != r.toLong()) errors += AssertionError("round $r rc=$rc cnt=$cnt")
                    if (r % 10 == 0) {
                        val now = System.currentTimeMillis()
                        println("  round $r: ${now - mark}ms / 10 rounds"); mark = now
                    }
                }
            } catch (t: Throwable) {
                errors += t
            } finally {
                mem.atomicWriteInt(ch + STATE, 3); mem.atomicNotify(ch + STATE, 1)  // stop
            }
        }
        driver.start()
        driver.join(60_000)
        val ms = System.currentTimeMillis() - t0
        println("BLOCKING single: ${rounds * 2 + 1} submits in ${ms}ms (${(rounds * 2 + 1) * 1000L / ms}/s)")
        assertTrue(!driver.isAlive, "60초 내 미완료 → lost-wakeup hang")
        assertTrue(errors.isEmpty(), "오류 ${errors.size}건: ${errors.toList().take(5)}")
    }

    @Test
    fun `다중 워커 - 동시 브리지 부하`() {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val mem = ByteBufferMemory(MemoryLimits(2048, 32768, true))
        val main = newMain(mem, errors)

        val nWorkers = 4
        val rounds = 30
        val t0 = System.currentTimeMillis()
        val chans = (0 until nWorkers).map {
            val ch = main.export("malloc").apply(4096L)[0].toInt()
            assertEquals(0, main.export("start_worker_blocking").apply(ch.toLong())[0].toInt(), "worker $it spawn")
            ch
        }

        val drivers = chans.mapIndexed { idx, ch ->
            Thread {
                try {
                    submit(mem, ch, "CREATE TABLE t(x)")
                    for (r in 1..rounds) {
                        submit(mem, ch, "INSERT INTO t VALUES ($r)")
                        val (rc, cnt) = submit(mem, ch, "SELECT count(*) FROM t")
                        if (rc != 0 || cnt != r.toLong()) errors += AssertionError("w$idx round $r rc=$rc cnt=$cnt")
                    }
                } catch (t: Throwable) {
                    errors += t
                } finally {
                    mem.atomicWriteInt(ch + STATE, 3); mem.atomicNotify(ch + STATE, 1)
                }
            }
        }
        drivers.forEach { it.start() }
        val deadline = System.currentTimeMillis() + 120_000
        drivers.forEach { it.join((deadline - System.currentTimeMillis()).coerceAtLeast(1)) }
        val alive = drivers.count { it.isAlive }
        val ms = System.currentTimeMillis() - t0
        println("BLOCKING multi: ${nWorkers}w x ${rounds * 2 + 1} submits in ${ms}ms")
        assertTrue(alive == 0, "$alive 워커가 120초 내 미완료 → herd/lost-wakeup hang")
        assertTrue(errors.isEmpty(), "오류 ${errors.size}건: ${errors.toList().take(5)}")
    }
}
