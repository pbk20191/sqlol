package org.example.sqlite.core

import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.wasm.types.MemoryLimits
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test

/**
 * 가설 검증: ByteBufferMemory 의 plain writeI32/readInt 가 word-원자성이 없어 동시 접근 시 torn read 가
 * 나는가? (SQLite wal-index 는 정렬된 32비트 접근의 자연 원자성을 가정 → tearing 이면 wal-index 손상.)
 *
 * writer 가 두 값(0x11111111 / 0x22222222)만 교대로 쓰고, reader 들이 그 외 값을 한 번이라도 보면 tearing.
 */
class TearingProbeTest {

    @Test
    fun `plain writeI32-readInt tearing 검출`() {
        val mem = ByteBufferMemory(MemoryLimits(2, 8, true))
        val addr = 1024
        val A = 0x11111111
        val B = 0x22222222
        val stop = AtomicBoolean(false)
        val torn = AtomicLong(0)
        val reads = AtomicLong(0)

        val writer = Thread {
            var t = true
            while (!stop.get()) { mem.writeI32(addr, if (t) A else B); t = !t }
        }
        val readers = (0 until 4).map {
            Thread {
                while (!stop.get()) {
                    val v = mem.readInt(addr)
                    reads.incrementAndGet()
                    if (v != A && v != B) torn.incrementAndGet()
                }
            }
        }
        writer.start(); readers.forEach { it.start() }
        Thread.sleep(3000)
        stop.set(true)
        writer.join(); readers.forEach { it.join() }

        println("TEARING: reads=${reads.get()} torn=${torn.get()}")
        // 진단용: tearing 이 나면 0 보다 큼. (assert 하지 않고 카운트만 보고 — 결론은 로그로)
    }
}
