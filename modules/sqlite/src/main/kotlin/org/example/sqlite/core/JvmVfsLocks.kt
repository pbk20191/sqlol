package org.example.sqlite.core

import com.example.wasm.JvmVfsModule_Vfs
import java.nio.channels.FileLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 커스텀 VFS(os_jvm) 의 락/shm-락을 JVM 인메모리로 구현.
 * 파일명(name)으로 같은 파일을 묶고, conn(파일 핸들 식별자)으로 커넥션을 구분한다.
 * 모든 메서드는 SQLite 결과코드(0=OK, 5=BUSY)를 반환.
 */
internal class JvmVfsLocks  {
    companion object {
        const val OK = 0
        const val BUSY = 5

        // 파일 락 레벨
        const val NONE = 0
        const val SHARED = 1
        const val RESERVED = 2
        const val PENDING = 3
        const val EXCLUSIVE = 4

        // xShmLock flags (sqlite3.h)
        const val SHM_UNLOCK = 1
        const val SHM_LOCK = 2
        const val SHM_SHARED = 4
        const val SHM_EXCLUSIVE = 8
        const val SHM_NLOCK = 8
    }

    private class FileState {
        val lock = ReentrantLock()
        val levels = HashMap<Int, Int>()                 // conn -> 현재 락 레벨
    }

    private class ShmState {
        val lock = ReentrantLock()
        val shared = Array(SHM_NLOCK) { HashSet<Int>() } // 슬롯별 공유 보유 conn 집합
        val excl = IntArray(SHM_NLOCK) { -1 }            // 슬롯별 배타 보유 conn (-1=없음)
    }

    private val files = ConcurrentHashMap<String, FileState>()
    private val shms = ConcurrentHashMap<String, ShmState>()

    private fun fileState(name: String) = files.getOrPut(name) { FileState() }
    private fun shmState(name: String) = shms.getOrPut(name) { ShmState() }

        // ---- 파일 락 ----
    fun lock(conn: Int, name: String, target: Int): Int {
        val st = fileState(name)
            FileLock::acquiredBy
//        ConcurrentHashMap<String, FileState>().getOrPut()
        st.lock.withLock {
            val cur = st.levels[conn] ?: NONE
            if (cur >= target) return OK
            fun othersAtLeast(level: Int) = st.levels.any { (c, l) -> c != conn && l >= level }
            val blocked = when (target) {
                SHARED -> othersAtLeast(PENDING)        // PENDING/EXCLUSIVE 가 있으면 새 SHARED 불가
                RESERVED -> othersAtLeast(RESERVED)     // RESERVED 는 한 커넥션만
                PENDING -> othersAtLeast(PENDING)
                EXCLUSIVE -> othersAtLeast(SHARED)      // 다른 어떤 락도 없어야 함
                else -> false
            }
            if (blocked) return BUSY
            st.levels[conn] = target
            return OK
        }

    }

    fun unlock(conn: Int, name: String, target: Int): Int {
        val st = fileState(name)
        st.lock.withLock {
            if (target <= NONE) st.levels.remove(conn) else st.levels[conn] = target
            return OK
        }
    }

    /** 다른 커넥션이 RESERVED 이상을 쥐고 있으면 1 */
    fun checkReserved(conn: Int, name: String): Int {
        val st = fileState(name)
        st.lock.withLock {
            return if (st.levels.any { (c, l) -> c != conn && l >= RESERVED }) 1 else 0

        }

    }

    // ---- shm 락 (offset..offset+n-1 슬롯) ----
    fun shmLock(conn: Int, name: String, offset: Int, n: Int, flags: Int): Int {
        val st = shmState(name)
        val slots = offset until (offset + n)
        st.lock.withLock {
            when {
                flags and SHM_UNLOCK != 0 -> {
                    for (s in slots) { st.shared[s].remove(conn); if (st.excl[s] == conn) st.excl[s] = -1 }
                    return OK
                }
                flags and SHM_EXCLUSIVE != 0 -> {
                    for (s in slots) {
                        if (st.excl[s] != -1 && st.excl[s] != conn) return BUSY
                        if (st.shared[s].any { it != conn }) return BUSY
                    }
                    for (s in slots) { st.excl[s] = conn; st.shared[s].remove(conn) }
                    return OK
                }
                else -> { // SHARED
                    for (s in slots) if (st.excl[s] != -1 && st.excl[s] != conn) return BUSY
                    for (s in slots) st.shared[s].add(conn)
                    return OK
                }
            }
        }
    }

    /** 커넥션 종료 시 해당 conn 의 shm 락 전부 해제 */
    fun shmUnmap(conn: Int, name: String): Int {
        val st = shmState(name)
        st.lock.withLock {
            for (s in 0 until SHM_NLOCK) { st.shared[s].remove(conn); if (st.excl[s] == conn) st.excl[s] = -1 }

        }

        return OK
    }


}
