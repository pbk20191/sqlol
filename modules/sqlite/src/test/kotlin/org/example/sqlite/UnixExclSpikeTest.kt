package org.example.sqlite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 스파이크: **`unix-excl` VFS 로 os_jvm.c/JvmVfsLocks/ShmArena 를 대체할 수 있는가?**
 *
 * unix-excl = 프로세스가 파일을 독점하는 대신 wal-index 를 **힙에 두고 같은 프로세스의 커넥션끼리
 * 공유** (os_unix.c UNIXFILE_EXCL / unixShmNode 힙 경로). 우리는 단일 프로세스(한 JVM)라서 이게
 * 동작하면 커스텀 VFS 전체가 불필요해진다.
 *
 * 판정할 전제:
 *  (b) WASI 에서 unix-excl 의 락/shm 경로가 동작하는가 — 같은 파일 2커넥션이 WAL 진입 + 상호 가시.
 *  (c) 커넥션을 같은 unixShmNode 로 묶는 열쇠 = **inode 식별(st_dev+st_ino)** 을 chicory-wasi 가
 *      진짜 값으로 주는가 — 가짜(전부 동일)면 **다른 DB 파일들이 wal-index 를 공유**해 오염된다.
 *      → 다른 파일 2개의 격리를 직접 검증.
 *
 * **판정 (2026-06-11): 기각.** 3 테스트 전부 rc=10(SQLITE_IOERR) — WAL 진입은 물론 평범한 INSERT 의
 * 쓰기 락 획득부터 실패. 원인: WASI(preview1)에는 파일 락 시스콜이 없어 os_unix 의 osFcntl(F_SETLK)
 * 락 경로가 동작 불가 — unix-excl 은 첫 쓰기에서 프로세스 배타 락이 필수라 즉사한다.
 * os_jvm.c 가 정확히 이 구멍(xLock/xShm)을 호스트 import 로 메우는 물건 — shim 은 편의가 아니라
 * WASI 에 없는 기능의 대체물. (우회로인 xSetSystemCall 로 fcntl 교체 + 테이블 host fn 주입은 가능하나
 * 복잡도가 os_jvm 이상이라 무의미)
 */
@org.junit.jupiter.api.Disabled("판정 완료: 기각 — 판정 기록은 KDoc/BUILD.md §9.1 참조")
class UnixExclSpikeTest {

    private val RW_CREATE = 0x2 or 0x4   // SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE

    private fun openV2(rt: JvmVfsRuntime, path: String, vfs: String): Int {
        val pp = rt.malloc(4)
        val rc = rt.exports.sqlite3OpenV2(rt.cString(path), pp, RW_CREATE, rt.cString(vfs))
        val db = rt.mem.readInt(pp)
        rt.free(pp)
        check(rc == 0) { "open_v2($path, vfs=$vfs) rc=$rc" }
        return db
    }

    @Test
    fun `같은 파일 2커넥션 - WAL 진입 + 상호 가시성`() {
        val dir = Files.createTempDirectory("uexcl")
        JvmVfsRuntime(mapOf("/db" to dir)).use { rt ->
            check(rt.exports.sqlite3VfsFind(rt.cString("unix-excl")) != 0) { "unix-excl 미등록" }

            val a = openV2(rt, "/db/x.db", "unix-excl")
            val b = openV2(rt, "/db/x.db", "unix-excl")

            assertEquals(0, rt.exec(a, "PRAGMA journal_mode=WAL"))
            assertEquals("wal", rt.scalarText(a, "PRAGMA journal_mode")) { "WAL 폴백 — shm 경로 부재" }

            assertEquals(0, rt.exec(a, "CREATE TABLE t(v INTEGER)"))
            repeat(5) { assertEquals(0, rt.exec(a, "INSERT INTO t(v) VALUES ($it)")) }

            // B 가 A 의 커밋을 본다 = 힙 wal-index 가 커넥션 간 공유된다 (unixShmNode/inode 공유)
            assertEquals("wal", rt.scalarText(b, "PRAGMA journal_mode"))
            assertEquals(5L, rt.scalarLong(b, "SELECT count(*) FROM t"))

            // 교차 방향: B 쓰기 → A 읽기
            assertEquals(0, rt.exec(b, "INSERT INTO t(v) VALUES (99)"))
            assertEquals(6L, rt.scalarLong(a, "SELECT count(*) FROM t"))
            assertEquals("ok", rt.scalarText(a, "PRAGMA integrity_check"))

            rt.closeDb(b); rt.closeDb(a)
        }
    }

    @Test
    fun `다른 파일 2개 - 격리 (inode 식별 판정)`() {
        val dir = Files.createTempDirectory("uexcl")
        JvmVfsRuntime(mapOf("/db" to dir)).use { rt ->
            val a = openV2(rt, "/db/a.db", "unix-excl")
            val b = openV2(rt, "/db/b.db", "unix-excl")
            rt.exec(a, "PRAGMA journal_mode=WAL"); rt.exec(b, "PRAGMA journal_mode=WAL")

            rt.exec(a, "CREATE TABLE ta(v INTEGER)")
            rt.exec(b, "CREATE TABLE tb(v INTEGER)")
            repeat(3) { rt.exec(a, "INSERT INTO ta(v) VALUES (1)") }
            repeat(7) { rt.exec(b, "INSERT INTO tb(v) VALUES (2)") }

            // inode 가 가짜로 전부 동일하면 a/b 가 unixShmNode 를 공유해 여기서 오염/오류가 난다
            assertEquals(3L, rt.scalarLong(a, "SELECT count(*) FROM ta"))
            assertEquals(7L, rt.scalarLong(b, "SELECT count(*) FROM tb"))
            assertEquals("ok", rt.scalarText(a, "PRAGMA integrity_check"))
            assertEquals("ok", rt.scalarText(b, "PRAGMA integrity_check"))
            // ta 가 b 에 보이면 안 됨 (스키마 격리)
            assertEquals(0L, rt.scalarLong(b, "SELECT count(*) FROM sqlite_master WHERE name='ta'"))

            rt.closeDb(b); rt.closeDb(a)
        }
    }

    @Test
    fun `동시성 스모크 - 워커 2개가 unix-excl 로 같은 파일 동시 접근`() {
        val dir = Files.createTempDirectory("uexcl")
        JvmVfsRuntime(mapOf("/db" to dir)).use { rt ->
            val init = openV2(rt, "/db/c.db", "unix-excl")
            rt.exec(init, "PRAGMA journal_mode=WAL")
            rt.exec(init, "CREATE TABLE t(v INTEGER)")
            rt.closeDb(init)

            // park-carrier (§9.2): child 를 순차 spawn 후, body 는 평범한 VT 가 child exports 로 직접 실행
            val children = (1..2).map { rt.spawnPthread() }
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val vts = children.mapIndexed { i, c ->
                val k = i + 1
                Thread.ofVirtual().start {
                    try {
                        val x = c.exports
                        // 문자열은 반드시 자기(child) 인스턴스로 — main exports 를 워커에서 부르면
                        // 워커들이 main 의 __stack_pointer 를 공유하게 된다
                        fun cs(s: String): Int {
                            val b = s.toByteArray(Charsets.UTF_8)
                            val p = x.malloc(b.size + 1)
                            rt.mem.write(p, b); rt.mem.writeByte(p + b.size, 0)
                            return p
                        }
                        val pp = x.malloc(4)
                        val rc = x.sqlite3OpenV2(cs("/db/c.db"), pp, RW_CREATE, cs("unix-excl"))
                        val db = rt.mem.readInt(pp); x.free(pp)
                        check(rc == 0) { "worker open rc=$rc" }
                        x.sqlite3Exec(db, cs("PRAGMA busy_timeout=5000"), 0, 0, 0)
                        val ins = cs("INSERT INTO t(v) VALUES ($k)")
                        repeat(25) {
                            var r: Int
                            do { r = x.sqlite3Exec(db, ins, 0, 0, 0) } while (r == 5)
                            check(r == 0) { "insert rc=$r" }
                        }
                        x.sqlite3CloseV2(db)
                    } catch (t: Throwable) {
                        errors += t
                    }
                }
            }
            vts.forEach { it.join(60_000) }
            check(errors.isEmpty()) { "워커 실패; errors=$errors" }
            children.forEach { rt.closeChild(it) }

            val v = openV2(rt, "/db/c.db", "unix-excl")
            assertEquals(50L, rt.scalarLong(v, "SELECT count(*) FROM t"))
            assertEquals("ok", rt.scalarText(v, "PRAGMA integrity_check"))
            rt.closeDb(v)
        }
    }
}
