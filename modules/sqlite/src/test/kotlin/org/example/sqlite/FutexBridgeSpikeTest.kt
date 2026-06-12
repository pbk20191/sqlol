package org.example.sqlite

import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.wasm.types.MemoryLimits
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 길 1 검증: 폴링 없이 ByteBufferMemory 의 블로킹 futex(atomicWait/atomicNotify)만으로
 * (a) lost-wakeup 이 없고 (b) plain payload 가시성이 보장되는가?
 *
 * wasm `atomic.wait32` 는 Chicory 에서 `Memory.atomicWait` 로 컴파일되므로, 결함이 있다면
 * wasm/AOT 없이도 여기서 그대로 드러난다. 그래서 C 재빌드 없이 1.7.5 의 release/acquire 보장만
 * 순수 JVM 으로 격리해 판가름한다.
 *
 * 모든 대기는 timeout=-1(무한). 어디서든 wakeup 을 놓치면 watchdog 타임아웃으로 hang 이 드러난다.
 * payload 는 매 라운드 r 로 덮어쓰므로, stale read 가 일어나면 직전 값(r-1)으로 검출된다.
 */
class FutexBridgeSpikeTest {

    private val INF = -1L

    /**
     * 단일 핑퐁: signaler 가 plain payload 를 쓴 뒤 atomicWriteInt(REQ)+atomicNotify 로 깨우고,
     * waiter 는 atomicWait(REQ) 로 무한 블록. waiter 는 payload 검증 후 DONE 으로 ack.
     * DONE ack 로 라운드가 lockstep 직렬화되어 payload 덮어쓰기 경합이 없다.
     */
    @Test
    fun `핑퐁 - 무폴링 블로킹 futex 의 lost-wakeup·가시성`() {
        val mem = ByteBufferMemory(MemoryLimits(4, 16, true))
        val req = 1024
        val done = 1028
        val pay = 2048
        val words = 64
        val rounds = 20_000
        val err = ConcurrentLinkedQueue<String>()

        val waiter = Thread.ofVirtual().unstarted {
            try {
                for (r in 1..rounds) {
                    var v = mem.atomicReadInt(req)
                    while (v < r) {                       // futex wait loop
                        mem.atomicWait(req, v, INF)       // sleep while REQ==v (무한)
                        v = mem.atomicReadInt(req)
                    }
                    // REQ 의 monitor acquire 이후 → 그 앞 plain payload 쓰기가 보여야 한다
                    for (i in 0 until words) {
                        val got = mem.readInt(pay + i * 4)
                        if (got != r) err += "stale payload round=$r idx=$i got=$got"
                    }
                    mem.atomicWriteInt(done, r)
                    mem.atomicNotify(done, 1)
                }
            } catch (t: Throwable) {
                err += "waiter: $t"
            }
        }
        waiter.start()

        val signaler = Thread.ofVirtual().unstarted {
            try {
                for (r in 1..rounds) {
                    for (i in 0 until words) mem.writeI32(pay + i * 4, r)  // plain store
                    mem.atomicWriteInt(req, r)            // release fence (monitor)
                    mem.atomicNotify(req, 1)
                    var v = mem.atomicReadInt(done)
                    while (v < r) {
                        mem.atomicWait(done, v, INF)
                        v = mem.atomicReadInt(done)
                    }
                }
            } catch (t: Throwable) {
                err += "signaler: $t"
            }
        }
        signaler.start()

        signaler.join(60_000)
        waiter.join(5_000)
        assertTrue(!signaler.isAlive && !waiter.isAlive, "60초 내 미완료 → lost-wakeup hang 의심")
        assertTrue(err.isEmpty(), "실패 ${err.size}건: ${err.toList().take(5)}")
    }

    /**
     * 다중 워커가 서로 다른 주소에서 동시에 핑퐁. notify 가 항상 notifyAll 이라도, 워커별 주소가
     * 달라 각자 다른 WaitState monitor 에서 대기 → thundering herd/데드락이 없어야 한다.
     */
    @Test
    fun `다중 워커 - 주소 분리로 herd·데드락 회피`() {
        val mem = ByteBufferMemory(MemoryLimits(8, 16, true))
        val pairs = 6
        val rounds = 8_000
        val stride = 4096            // 워커별 채널 간격 (다른 주소 = 다른 monitor)
        val err = ConcurrentLinkedQueue<String>()

        val workers = (0 until pairs).map { p ->
            val base = 4096 + p * stride
            val req = base
            val done = base + 4
            val pay = base + 16
            val waiter = Thread.ofVirtual().unstarted {
                try {
                    for (r in 1..rounds) {
                        var v = mem.atomicReadInt(req)
                        while (v < r) { mem.atomicWait(req, v, INF); v = mem.atomicReadInt(req) }
                        val got = mem.readInt(pay)
                        if (got != r) err += "pair=$p stale round=$r got=$got"
                        mem.atomicWriteInt(done, r)
                        mem.atomicNotify(done, 1)
                    }
                } catch (t: Throwable) { err += "pair=$p waiter: $t" }
            }
            val signaler = Thread.ofVirtual().unstarted {
                try {
                    for (r in 1..rounds) {
                        mem.writeI32(pay, r)
                        mem.atomicWriteInt(req, r)
                        mem.atomicNotify(req, 1)
                        var v = mem.atomicReadInt(done)
                        while (v < r) { mem.atomicWait(done, v, INF); v = mem.atomicReadInt(done) }
                    }
                } catch (t: Throwable) { err += "pair=$p signaler: $t" }
            }
            waiter to signaler
        }
        workers.forEach { it.first.start(); it.second.start() }
        val deadline = System.currentTimeMillis() + 90_000
        workers.forEach { (w, s) ->
            s.join((deadline - System.currentTimeMillis()).coerceAtLeast(1))
            w.join((deadline - System.currentTimeMillis()).coerceAtLeast(1))
        }
        val alive = workers.count { it.first.isAlive || it.second.isAlive }
        assertTrue(alive == 0, "$alive 쌍이 90초 내 미완료 → herd/데드락 의심")
        assertTrue(err.isEmpty(), "실패 ${err.size}건: ${err.toList().take(5)}")
    }
}
