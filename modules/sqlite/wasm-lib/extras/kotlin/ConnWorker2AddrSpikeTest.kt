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
 * 길 1 해법 검증: 방향별 주소 분리(req/done)로 **폴링 없이** JVM↔wasm 블로킹 브리지가 동작하는가?
 *
 * Spike B(단일 `state` 워드)의 교착 원인은 가시성이 아니라 **한 futex 주소 양방향 신호의 wakeup 도둑질**
 * 이었다(wasm atomic.load/store 는 인터프리터에서 atomicReadInt/atomicWriteInt = monitor 동기화로 낮춰짐).
 * 워커는 `req` 에서만, 드라이버는 `done` 에서만 대기 → 각 주소 waiter 1종류 → pendingWakeups 가로채기 불가.
 *
 * 워커 `wait32(req, r, -1)` 무한 대기, JVM `atomicWriteInt(req)`+`atomicNotify(req)` / `atomicWait(done)`.
 * 폴링·타임아웃 백스톱 전혀 없음. 통과하면 길 1 은 "주소만 올바로 쓰면" 성립.
 *
 * 채널: req@0, done@4, rc@8, result@16(i64), sql@24(cstr)
 */
class ConnWorker2AddrSpikeTest {

    private val REQ = 0
    private val DONE = 4
    private val RC = 8
    private val RESULT = 16
    private val SQL = 24

    private fun newMain(mem: ByteBufferMemory, errors: ConcurrentLinkedQueue<Throwable>): Instance {
        val mod = javaClass.getResourceAsStream("/sqlite3-threads.wasm").use { Parser.parse(it) }
        val wasi = WasiPreview1.builder()
            .withOptions(WasiOptions.builder().withStdout(System.out).withStderr(System.err).build()).build()
        val threadSpawn = HostFunction("wasi", "thread-spawn", listOf(ValType.I32), listOf(ValType.I32)) { parent: Instance, args: LongArray ->
            val startArg = args[0]
            val thread = Thread.ofPlatform().name("2a-worker").start {
                try {
                    val w = Instance.builder(parent.module()).withImportValues(parent.imports())
                        .withStart(false).withInitialize(true).build()
                    w.exports().function("wasi_thread_start").apply(Thread.currentThread().threadId(), startArg)
                } catch (t: Throwable) { errors += t }
            }
            longArrayOf(thread.threadId())
        }
        val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).addFunction(threadSpawn)
            .addMemory(ImportMemory("env", "memory", mem)).build()
        val main = Instance.builder(mod).withImportValues(imports).withStart(false).withInitialize(true).build()
        main.export("_initialize").apply()
        return main
    }

    /** 폴링 없는 무한 블로킹 제출. doneSeq 로 완료 시퀀스를 맞춘다. */
    private class Driver(val mem: ByteBufferMemory, val ch: Int, val REQ: Int, val DONE: Int, val RC: Int, val RESULT: Int, val SQL: Int) {
        var doneSeq = 0
        fun submit(sql: String): Pair<Int, Long> {
            mem.writeCString(ch + SQL, sql)
            mem.atomicWriteInt(ch + REQ, 1)
            mem.atomicNotify(ch + REQ, 1)
            doneSeq++
            var d = mem.atomicReadInt(ch + DONE)
            while (d != doneSeq) {                 // done 까지 무한 블록 (폴링 없음)
                mem.atomicWait(ch + DONE, d, -1L)
                d = mem.atomicReadInt(ch + DONE)
            }
            return mem.readInt(ch + RC) to mem.readLong(ch + RESULT)
        }
        fun stop() { mem.atomicWriteInt(ch + REQ, 3); mem.atomicNotify(ch + REQ, 1) }
    }

    @Test
    fun `단일 워커 - 주소분리 무폴링 블로킹`() {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val mem = ByteBufferMemory(MemoryLimits(512, 32768, true))
        val main = newMain(mem, errors)
        val ch = main.export("malloc").apply(8192L)[0].toInt()
        assertEquals(0, main.export("start_worker_2addr").apply(ch.toLong())[0].toInt())

        val rounds = 1000
        val t0 = System.currentTimeMillis()
        val driver = Thread {
            val d = Driver(mem, ch, REQ, DONE, RC, RESULT, SQL)
            try {
                assertEquals(0, d.submit("CREATE TABLE t(x)").first)
                for (r in 1..rounds) {
                    d.submit("INSERT INTO t VALUES ($r)")
                    val (rc, cnt) = d.submit("SELECT count(*) FROM t")
                    if (rc != 0 || cnt != r.toLong()) errors += AssertionError("round $r rc=$rc cnt=$cnt")
                }
            } catch (t: Throwable) { errors += t } finally { d.stop() }
        }
        driver.start()
        driver.join(90_000)
        println("2ADDR single: ${rounds * 2 + 1} submits in ${System.currentTimeMillis() - t0}ms")
        assertTrue(!driver.isAlive, "90초 내 미완료 → 교착")
        assertTrue(errors.isEmpty(), "오류 ${errors.size}건: ${errors.toList().take(5)}")
    }

    @Test
    fun `다중 워커 - 주소분리 동시 무폴링`() {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val mem = ByteBufferMemory(MemoryLimits(2048, 32768, true))
        val main = newMain(mem, errors)
        val nWorkers = 4
        val rounds = 250
        val chans = (0 until nWorkers).map {
            val ch = main.export("malloc").apply(8192L)[0].toInt()
            assertEquals(0, main.export("start_worker_2addr").apply(ch.toLong())[0].toInt())
            ch
        }
        val t0 = System.currentTimeMillis()
        val drivers = chans.mapIndexed { idx, ch ->
            Thread {
                val d = Driver(mem, ch, REQ, DONE, RC, RESULT, SQL)
                try {
                    d.submit("CREATE TABLE t(x)")
                    for (r in 1..rounds) {
                        d.submit("INSERT INTO t VALUES ($r)")
                        val (rc, cnt) = d.submit("SELECT count(*) FROM t")
                        if (rc != 0 || cnt != r.toLong()) errors += AssertionError("w$idx round $r rc=$rc cnt=$cnt")
                    }
                } catch (t: Throwable) { errors += t } finally { d.stop() }
            }
        }
        drivers.forEach { it.start() }
        val deadline = System.currentTimeMillis() + 120_000
        drivers.forEach { it.join((deadline - System.currentTimeMillis()).coerceAtLeast(1)) }
        println("2ADDR multi: ${nWorkers}w x ${rounds * 2 + 1} submits in ${System.currentTimeMillis() - t0}ms")
        assertTrue(drivers.none { it.isAlive }, "120초 내 미완료 → 교착")
        assertTrue(errors.isEmpty(), "오류 ${errors.size}건: ${errors.toList().take(5)}")
    }
}
