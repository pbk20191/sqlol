package org.example.sqlite

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ThreadLocalRandom

/**
 * **호스트(OS) 레벨 단독 소유 가드** — "런타임 하나가 DB 파일을 독점한다"는 운영 전제를
 * OS advisory 락([FileChannel.tryLock])으로 강제한다. wasm 안(WASI)에는 파일 락 시스콜이 없지만,
 * 호스트 JVM 은 진짜 OS 프로세스이므로 여기서 잠근다 — unix-excl 이 하려던 보장의 JVM 판.
 *
 * **사이드카(`<db>.jvmlock`)에 거는 이유**: POSIX fcntl 락은 "프로세스가 그 파일의 **아무 fd 나
 * 닫으면** 락이 풀리는" 함정이 있다 — guest(wasi)가 워커 커넥션을 닫을 때마다 DB 파일 fd 를 닫으므로,
 * DB 파일 자체에 건 호스트 락은 수시로 증발한다. 사이드카는 아무도 안 여니 안전하다.
 *
 * **사이드카는 close 시 삭제한다** (잔재 미관 — JDBC 드라이버가 사용자 디렉터리에 영구 파일을 남기지
 * 않게). 삭제/재생성의 고전적 레이스("고아 inode 를 잠그고 소유로 착각")는 acquire 의 **토큰 왕복
 * 재검증**으로 닫는다: 락 획득 후 자기 fd 에 난수 토큰을 쓰고 경로를 새로 열어 읽었을 때 같은 값이면
 * fd 의 inode == 경로의 현재 inode (직전 소유자가 삭제했다면 불일치/ENOENT → 재시도).
 *
 * 보호 범위: ① 다른 프로세스의 이 라이브러리(또는 사이드카를 확인하는 협조적 도구) ② 같은 JVM 의
 * 중복 런타임 ([OverlappingFileLockException] 변환). **비협조적 네이티브 도구(예: sqlite3 CLI)는 못
 * 막는다** — 그쪽은 DB 파일의 fcntl 바이트만 보므로, 운영상 DB 디렉터리를 외부 도구와 공유하지 말 것.
 */
internal class DbOwnerLock private constructor(
    private val path: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        // 락을 쥔 채로 삭제 → 해제 (역순이면 삭제 전에 새 소유자가 옛 inode 를 잠글 수 있다).
        // Windows 는 열린 파일 삭제가 거부될 수 있음 — 그 경우 사이드카 잔존 (무해, 다음 acquire 가 재사용).
        runCatching { Files.deleteIfExists(path) }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(hostDir: Path, fileName: String): DbOwnerLock {
            val path = hostDir.resolve("$fileName.jvmlock")
            repeat(16) {
                val ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                val fl = try {
                    ch.tryLock()
                } catch (e: OverlappingFileLockException) {
                    ch.close()
                    throw IllegalStateException("DB '$fileName' 은 같은 JVM 의 다른 런타임이 사용 중입니다", e)
                }
                if (fl == null) {
                    ch.close()
                    throw IllegalStateException("DB '$fileName' 은 다른 프로세스가 사용 중입니다")
                }
                // 정체성 재검증 — 잠근 inode 가 아직 path 의 inode 인가 (토큰 왕복)
                val token = ThreadLocalRandom.current().nextLong()
                ch.write(ByteBuffer.allocate(8).putLong(0, token), 0)
                ch.force(false)
                val seen = runCatching {
                    FileChannel.open(path, StandardOpenOption.READ).use { c2 ->
                        val b = ByteBuffer.allocate(8)
                        c2.read(b, 0)
                        b.getLong(0)
                    }
                }.getOrNull()
                if (seen == token) return DbOwnerLock(path, ch, fl)
                // 고아 inode (직전 소유자가 close 에서 삭제) — 정리하고 재시도
                runCatching { fl.release() }
                runCatching { ch.close() }
            }
            throw IllegalStateException("DB '$fileName' 소유 락 획득 실패 (삭제/재생성 경합 지속)")
        }
    }
}
