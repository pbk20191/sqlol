package org.example.sqlite.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/**
 * 크래시 복구 (BUILD.md 후보 ②).
 *
 * 우리 구조의 크래시-임계 불변식: **wal-index 는 프로세스 사유 linear memory(ShmArena)라 크래시 시
 * 소멸하고, -wal 파일만 디스크에 영속**한다. 따라서 비정상 종료 후 새 프로세스가 DB 를 열면 반드시
 * 빈 wal-index 를 디스크 -wal 프레임에서 **재구성(WAL recovery)** 해야 한다 — 표준 SQLite walIndexRecover
 * 경로가 우리 os_jvm VFS(파일 I/O 는 WASI 실파일 위임, shm 만 인메모리) 위에서 정상 동작하는지 검증.
 *
 * 크래시 모사 = **디스크 파일 스냅샷**: 첫 런타임이 커밋한 직후(체크포인트 전) db+wal 을 새 디렉터리로
 * 복사하면 "커밋된 -wal 프레임 + wal-index 없음" = 프로세스가 막 죽은 디스크 상태와 동형.
 * (같은 JVM 에서 런타임을 abandon 하면 DbOwnerLock 의 FileChannel 락이 OverlappingFileLockException
 *  으로 잡혀 in-JVM 재오픈이 막히므로, 진짜 "프로세스 사망"은 파일 스냅샷으로만 충실히 모사된다.)
 *
 * 전제: JEP 491(JDK 24+).
 */
class CrashRecoveryTest {

    /** [src]/[name] 및 그 사이드파일(-wal 등 name* )을 [dst] 로 복사 = "크래시 순간의 디스크 상태" 캡처. */
    private fun snapshot(src: Path, name: String, dst: Path): List<String> {
        Files.createDirectories(dst)
        val copied = ArrayList<String>()
        Files.newDirectoryStream(src) { it.fileName.toString().startsWith(name) }.use { ds ->
            for (p in ds) {
                val fn = p.fileName.toString()
                if (fn.endsWith(".jvmlock")) continue   // 소유 락 사이드카는 크래시면 OS 가 해제 → 복사 안 함
                Files.copy(p, dst.resolve(fn), StandardCopyOption.REPLACE_EXISTING)
                copied += fn
            }
        }
        return copied
    }

    @Test
    fun `커밋 데이터는 크래시(wal-index 소멸) 후 -wal 복구로 살아남는다`() {
        val d1 = Files.createTempDirectory("crash-survive-1")
        val d2 = Files.createTempDirectory("crash-survive-2")
        val n = 500

        SqliteWal.open(d1, "app.db", readers = 1).use { db ->
            db.exec("PRAGMA wal_autocheckpoint=0")   // 체크포인트 금지 → 전 프레임이 -wal 에 잔류
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)")
            repeat(n) { i -> db.exec("INSERT INTO t(v) VALUES (?)", "row-$i") }
            assertEquals(n.toLong(), db.queryLong("SELECT count(*) FROM t"))

            // 커밋 직후, 체크포인트 전 디스크 상태를 캡처 = 프로세스가 막 죽은 시점
            val files = snapshot(d1, "app.db", d2)
            assertTrue(files.any { it == "app.db-wal" }) {
                "스냅샷에 -wal 이 있어야 복구를 검증하는 의미가 있다 (체크포인트돼 사라지면 안 됨): $files"
            }
        }

        // 새 런타임(=새 프로세스) — wal-index 는 빈 arena 에서 출발, -wal 에서 복구돼야 함
        SqliteWal.open(d2, "app.db", readers = 1).use { db ->
            assertEquals("ok", db.queryText("PRAGMA integrity_check"))
            assertEquals(n.toLong(), db.queryLong("SELECT count(*) FROM t"))
            // 복구 후 정상 쓰기 가능
            db.exec("INSERT INTO t(v) VALUES ('after-recovery')")
            assertEquals((n + 1).toLong(), db.queryLong("SELECT count(*) FROM t"))
        }
    }

    @Test
    fun `찢어진 마지막 프레임(파워로스 모사) — 체크섬이 거부, 직전 커밋으로 복구`() {
        val d1 = Files.createTempDirectory("crash-torn-1")
        val d2 = Files.createTempDirectory("crash-torn-2")

        SqliteWal.open(d1, "app.db", readers = 1).use { db ->
            db.exec("PRAGMA wal_autocheckpoint=0")
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)")
            db.exec("INSERT INTO t(v) VALUES ('committed-1')")   // 확실히 살아야 할 커밋
            repeat(200) { i -> db.exec("INSERT INTO t(v) VALUES (?)", "tail-$i") }
            snapshot(d1, "app.db", d2)
        }

        // -wal 꼬리를 잘라 마지막 프레임을 불완전하게 만든다 (파워로스 mid-frame-write 모사)
        val wal = d2.resolve("app.db-wal")
        val sz = Files.size(wal)
        assertTrue(sz > 100) { "wal 크기=$sz" }
        Files.newByteChannel(wal, StandardOpenOption.WRITE).use { ch -> ch.truncate(sz - 13) }

        // 복구는 손상 없이 직전 유효 커밋까지 — 코럽션 아님, 사용 가능해야 함
        SqliteWal.open(d2, "app.db", readers = 1).use { db ->
            assertEquals("ok", db.queryText("PRAGMA integrity_check")) { "찢어진 wal 이 코럽션을 유발하면 안 됨" }
            val c = db.queryLong("SELECT count(*) FROM t")
            assertTrue(c >= 1) { "최소한 첫 커밋(committed-1)은 살아야 한다 — count=$c" }
            // 복구 후 정상 쓰기 가능
            db.exec("INSERT INTO t(v) VALUES ('after-torn-recovery')")
            assertEquals(c + 1, db.queryLong("SELECT count(*) FROM t"))
        }
    }

    @Test
    fun `크래시로 남은 stale 소유락 사이드카는 재오픈을 막지 않는다`() {
        val dir = Files.createTempDirectory("crash-stalelock")

        // 1) 정상 생성 + 커밋
        SqliteWal.open(dir, "app.db", readers = 1).use { db ->
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY)")
            db.exec("INSERT INTO t(id) VALUES (1)")
        }

        // 2) 크래시한 프로세스가 남긴 잔재 사이드카 모사 — OS 가 락은 해제했지만 파일 바이트는 잔존.
        //    (close 가 삭제하므로, 명시적으로 다시 만들어 "정리 못 하고 죽음"을 모사)
        val sidecar = dir.resolve("app.db.jvmlock")
        Files.write(sidecar, ByteArray(8))
        assertTrue(Files.exists(sidecar))

        // 3) 재오픈 — DbOwnerLock.acquire 의 tryLock 이 (죽은 프로세스라 락 없음) 획득 + 토큰 왕복 통과
        SqliteWal.open(dir, "app.db", readers = 1).use { db ->
            assertEquals(1L, db.queryLong("SELECT count(*) FROM t"))
        }
    }

    @Test
    fun `진짜 프로세스 kill-9 후 재오픈 — OS 락 해제 + -wal 복구 (end-to-end)`() {
        val dir = Files.createTempDirectory("crash-kill")
        val n = 300
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val cp = System.getProperty("java.class.path")
        val proc = ProcessBuilder(
            javaBin, "-cp", cp, "org.example.sqlite.CrashSubprocessMain", dir.toString(), n.toString(),
        ).redirectErrorStream(true).start()

        try {
            // 자식이 커밋 후 "READY <count>" 를 낼 때까지 대기 (자식 출력은 디버깅 위해 모아둠)
            val out = StringBuilder()
            val reader = proc.inputStream.bufferedReader()
            var ready = false
            val deadline = System.nanoTime() + 60_000_000_000L
            while (System.nanoTime() < deadline) {
                val line = reader.readLine() ?: break
                out.append(line).append('\n')
                if (line.startsWith("READY")) {
                    assertEquals(n.toLong(), line.removePrefix("READY ").trim().toLong())
                    ready = true
                    break
                }
            }
            assertTrue(ready) { "자식 JVM 이 READY 를 못 냈다. 출력:\n$out" }

            // 크래시 = SIGKILL (shutdown hook 없음, 파일 정리 없음, 락 미해제 상태로 즉사)
            proc.destroyForcibly()
            assertTrue(proc.waitFor(30, TimeUnit.SECONDS)) { "자식이 30s 내 종료 안 됨" }
            assertFalse(proc.isAlive)
        } finally {
            proc.destroyForcibly()
        }

        // 부모(이 JVM)가 재오픈: OS 가 자식의 파일락을 해제했어야(아니면 "다른 프로세스가 사용 중") +
        // 자식이 -wal 에 커밋한 프레임이 빈 wal-index 위로 복구돼야 한다.
        SqliteWal.open(dir, "app.db", readers = 1).use { db ->
            assertEquals("ok", db.queryText("PRAGMA integrity_check"))
            assertEquals(n.toLong(), db.queryLong("SELECT count(*) FROM t"))
        }
    }
}
