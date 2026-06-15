package org.example.sqlite.core

import com.dylibso.chicory.runtime.Memory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 커스텀 VFS 의 wal-index(shm) 바이트를 공유 linear memory 의 arena 에서 분배.
 * 같은 (파일명, region) 은 같은 포인터를 돌려줘 모든 커넥션이 진짜로 공유한다.
 * thread-safe (다중 워커가 동시에 shm_map 호출).
 */
internal class ShmArena(private val mem: Memory) {
    var base = 0                    // arena 시작 주소(공유 메모리). 사용 전 설정
    private var cursor = 0

    private val lock = ReentrantLock()
    private val regions = HashMap<String, Int>()   // "name#region" -> ptr

    fun map(name: String, iRegion: Int, szRegion: Int, bExtend: Int): Int {
        lock.withLock {
            val key = "$name#$iRegion"
            regions[key]?.let { return it }
            if (bExtend == 0) return 0
            val ptr = base + cursor
            cursor += (szRegion + 15) and 0xF.inv()
            mem.fill(0, ptr, ptr + szRegion)   // StatelessBulkMemory 전제 — fill 도 position 무접촉
            regions[key] = ptr
            return ptr
        }
    }

    /** 마지막 커넥션 close(deleteFlag) 시: 해당 파일의 region 을 잊어 다음 세션이 0 으로 재초기화되게 */
    fun dropFile(name: String) {
        lock.withLock {
            regions.keys.removeAll { it.startsWith("$name#") }
        }
    }
}
