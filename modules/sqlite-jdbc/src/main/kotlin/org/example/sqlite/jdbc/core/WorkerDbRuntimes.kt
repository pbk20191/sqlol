package org.example.sqlite.jdbc.core

import org.example.sqlite.core.InternalRuntimeApi
import org.example.sqlite.core.SqliteDataSource
import org.example.sqlite.core.SqliteDataSource.IRuntimeHandle
import org.example.sqlite.core.WorkerDbPort
import java.nio.file.Path

/**
 * 런타임 공유 캐시 (JDBC 전용 정책 레이어).
 *  - 파일: canonical 경로별 런타임 1개를 refcount 로 공유 → 같은 파일의 JDBC 커넥션들이 같은 워커
 *    런타임에 합류 (다중 커넥션 WAL). core [DbOwnerLock] 의 "파일당 런타임 1개" 강제와 양립.
 *  - `:memory:`: 글로벌 런타임 1개 (lazy, refcount) — 워커마다 사유 인메모리 DB 라 공유 없음.
 *
 * 런타임 생성/종료(단독 소유 락 등 안전 불변식)는 core 의 opt-in SPI
 * ([SqliteDataSource.openRuntime]/[SqliteDataSource.openMemoryRuntime])가 담당하고,
 * 여기는 공유/refcount **정책**만 가진다.
 */
@OptIn(InternalRuntimeApi::class)
internal object WorkerDbRuntimes {
    private class FileEntry(val handle: IRuntimeHandle) {
        var refs = 0
    }

    private val files = HashMap<Path, FileEntry>()
    private var memHandle: IRuntimeHandle? = null
    private var memRefs = 0

    @Synchronized
    fun openFile(path: Path, readOnly: Boolean): WorkerDbPort {
        val abs = path.toAbsolutePath().normalize()
        val entry = files.getOrPut(abs) {
            val dir = abs.parent ?: error("절대 경로 필요: $abs")
            FileEntry(SqliteDataSource.openRuntime(dir, abs.fileName.toString()))
        }
        entry.refs++
        try {
            return entry.handle.spawnPort(readOnly) { releaseFile(abs) }
        } catch (t: Throwable) {
            releaseFile(abs)
            throw t
        }
    }

    @Synchronized
    private fun releaseFile(path: Path) {
        val entry = files[path] ?: return
        if (--entry.refs <= 0) {
            files.remove(path)
            entry.handle.close()
        }
    }

    @Synchronized
    fun openMemory(readOnly: Boolean): WorkerDbPort {
        val handle = memHandle ?: SqliteDataSource.openMemoryRuntime().also { memHandle = it }
        memRefs++
        try {
            return handle.spawnPort(readOnly) { releaseMemory() }
        } catch (t: Throwable) {
            releaseMemory()
            throw t
        }
    }

    @Synchronized
    private fun releaseMemory() {
        if (--memRefs <= 0) {
            memHandle?.close()
            memHandle = null
        }
    }
}
