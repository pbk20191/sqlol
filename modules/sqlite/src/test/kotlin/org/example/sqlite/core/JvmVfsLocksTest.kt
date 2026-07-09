package org.example.sqlite.core

import org.example.sqlite.core.JvmVfsLocks.Companion.BUSY
import org.example.sqlite.core.JvmVfsLocks.Companion.EXCLUSIVE
import org.example.sqlite.core.JvmVfsLocks.Companion.NONE
import org.example.sqlite.core.JvmVfsLocks.Companion.OK
import org.example.sqlite.core.JvmVfsLocks.Companion.RESERVED
import org.example.sqlite.core.JvmVfsLocks.Companion.SHARED
import org.example.sqlite.core.JvmVfsLocks.Companion.SHM_EXCLUSIVE
import org.example.sqlite.core.JvmVfsLocks.Companion.SHM_LOCK
import org.example.sqlite.core.JvmVfsLocks.Companion.SHM_SHARED
import org.example.sqlite.core.JvmVfsLocks.Companion.SHM_UNLOCK
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stage 2: 락 상태기계 시맨틱 단위 검증 (순수 JVM, wasm 무관) */
class JvmVfsLocksTest {

    private val f = "/db/test.db"

    @Test
    fun `파일 락 - SHARED 공존, RESERVED 단독, EXCLUSIVE 배타`() {
        val L = JvmVfsLocks()
        assertEquals(OK, L.lock(1, f, SHARED))      // conn1 SHARED
        assertEquals(OK, L.lock(2, f, SHARED))      // conn2 SHARED (공존 OK)

        assertEquals(OK, L.lock(1, f, RESERVED))    // conn1 RESERVED
        assertEquals(BUSY, L.lock(2, f, RESERVED))  // conn2 RESERVED → 단독이라 BUSY
        assertEquals(0, L.checkReserved(1, f))      // 자기 자신은 0
        assertEquals(1, L.checkReserved(2, f))      // 남이 RESERVED → 1

        assertEquals(BUSY, L.lock(1, f, EXCLUSIVE)) // conn2 가 SHARED 보유 → BUSY
        assertEquals(OK, L.unlock(2, f, NONE))      // conn2 해제
        assertEquals(OK, L.lock(1, f, EXCLUSIVE))   // 이제 EXCLUSIVE 가능

        assertEquals(BUSY, L.lock(2, f, SHARED))    // EXCLUSIVE 중엔 새 SHARED 불가
        assertEquals(OK, L.unlock(1, f, NONE))
        assertEquals(OK, L.lock(2, f, SHARED))      // 풀리면 가능
    }

    @Test
    fun `shm 락 - 공유 다중, 배타 단독`() {
        val L = JvmVfsLocks()
        val sh = SHM_LOCK or SHM_SHARED
        val ex = SHM_LOCK or SHM_EXCLUSIVE

        assertEquals(OK, L.shmLock(1, f, 0, 1, sh))   // conn1 슬롯0 공유
        assertEquals(OK, L.shmLock(2, f, 0, 1, sh))   // conn2 슬롯0 공유 (공존)
        assertEquals(BUSY, L.shmLock(1, f, 0, 1, ex)) // 공유 보유자 있어 배타 불가

        assertEquals(OK, L.shmLock(1, f, 0, 1, SHM_UNLOCK))
        assertEquals(OK, L.shmLock(2, f, 0, 1, SHM_UNLOCK))
        assertEquals(OK, L.shmLock(1, f, 0, 1, ex))   // 모두 풀려 배타 가능
        assertEquals(BUSY, L.shmLock(2, f, 0, 1, sh)) // 배타 중엔 공유 불가
        assertEquals(BUSY, L.shmLock(2, f, 0, 1, ex)) // 배타 중복 불가

        L.shmUnmap(1, f)                              // conn1 종료 → 전부 해제
        assertEquals(OK, L.shmLock(2, f, 0, 1, ex))   // 이제 가능
    }

    @Test
    fun `shm 락 - 슬롯 범위 밖 요청은 예외 대신 에러 rc`() {
        // xShmLock 은 wasm 호스트 함수에서 불린다 — Java 예외가 wasm 을 관통하면 안 되므로
        // (BUILD.md 불변식 3) 잘못된 범위는 AIOOBE 가 아니라 SQLITE_MISUSE(21) 를 반환해야 한다.
        val L = JvmVfsLocks()
        val sh = SHM_LOCK or SHM_SHARED
        assertEquals(21, L.shmLock(1, f, 7, 4, sh))    // 7+4 > SHM_NLOCK(8)
        assertEquals(21, L.shmLock(1, f, -1, 2, sh))   // 음수 offset
        assertEquals(21, L.shmLock(1, f, 0, 0, sh))    // n=0 (no-op 요청도 규약 위반)
        assertEquals(OK, L.shmLock(1, f, 7, 1, sh))    // 경계 정상 케이스는 여전히 OK
    }
}
