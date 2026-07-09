package org.example.sqlite.core

import run.endive.wasi.WasiOptions
import org.example.sqlite.DbCoordinator
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * SQLite 데이터베이스 커넥션. 하나의 WASM 인스턴스를 점유한다.
 *
 * 순수 JVM 동작 (네이티브 코드/JNI 없음). 단일 커넥션 객체는 thread-safe 하지 않으므로
 * 하나의 스레드에서만 사용한다. 단, [openFile] 로 같은 파일을 연 **여러** 커넥션을
 * 각자 다른 스레드에서 동시에 쓰는 것은 [DbCoordinator] 가 조율한다 (다중 reader + 단일 writer).
 */
class SqliteDb private constructor(
    private val n: Native,
    private val handle: Int,
    /** 같은 파일을 공유하는 커넥션들 사이의 락. :memory: 등 비공유면 null */
    private val fileLock: ReentrantReadWriteLock?,
) : AutoCloseable {

    /** 현재 이 커넥션이 write lock 을 점유 중인가 (= 트랜잭션 진행 중) */
    private var holdsWrite = false

    val libVersion: String
        get() = n.readCString(n.exports.sqlite3Libversion())

    /** SQLite 가 autocommit 모드인가 (트랜잭션이 열려있지 않은가) */
    private fun inAutocommit(): Boolean = n.exports.sqlite3GetAutocommit( handle) != 0

    /**
     * 쓰기 경로. write lock 을 잡고 실행하며, 작업 후 여전히 autocommit 이면 해제한다.
     * BEGIN 으로 트랜잭션이 열리면 autocommit 이 false 가 되어 lock 을 계속 유지하고,
     * COMMIT/ROLLBACK 으로 autocommit 이 돌아오면 그때 해제한다 (트랜잭션 전체를 배타 보장).
     */
    private fun <T> writing(block: () -> T): T {
        val lock = fileLock ?: return block()
        if (!holdsWrite) {
            lock.writeLock().lock()
            holdsWrite = true
        }
        try {
            return block()
        } finally {
            if (holdsWrite && inAutocommit()) {
                holdsWrite = false
                lock.writeLock().unlock()
            }
        }
    }

    /** 읽기 경로. 트랜잭션 진행 중이면 이미 잡은 write lock 을 재사용, 아니면 read lock. */
    private fun <T> reading(block: () -> T): T {
        val lock = fileLock ?: return block()
        if (holdsWrite) return block()
        lock.readLock().lock()
        try {
            return block()
        } finally {
            lock.readLock().unlock()
        }
    }

    /** 결과 집합이 없는 SQL(들) 실행. DDL/DML/PRAGMA/BEGIN/COMMIT 등. */
    fun exec(sql: String): Unit = writing { execRaw(sql) }

    private fun execRaw(sql: String) {
        val ptr = n.cString(sql)
        val rc = n.exports.sqlite3Exec( handle, ptr, 0, 0, 0)
        n.free(ptr)
        if (rc != SQLITE_OK) error("exec rc=$rc: ${n.errmsg(handle)} (sql=$sql)")
    }

    /**
     * 단일 SQL 문을 prepared statement 로 컴파일 (저수준 API).
     * 주의: 직접 step 하는 동안의 락 조율은 호출자 책임이다. 공유 파일에서는 가급적
     * [exec]/[query]/[update]/[transaction] 을 사용하라.
     */
    fun prepare(sql: String): SqliteStmt {
        val ppStmt = n.malloc(4)
        val sqlPtr = n.cString(sql)

        val rc = n.exports.sqlite3PrepareV2(handle, sqlPtr, -1, ppStmt, 0)
        val stmt = n.readInt(ppStmt)
        n.free(ppStmt)
        n.free(sqlPtr)
        if (rc != SQLITE_OK) error("prepare rc=$rc: ${n.errmsg(handle)} (sql=$sql)")
        return SqliteStmt(n, stmt, handle)
    }

    /** SELECT 실행. 파라미터를 1-based 로 바인딩하고 각 행을 [mapper] 로 변환해 리스트 반환 */
    fun <T> query(sql: String, vararg params: Any?, mapper: (SqliteStmt) -> T): List<T> = reading {
        prepare(sql).use { st ->
            params.forEachIndexed { i, v -> st.bind(i + 1, v) }
            val out = ArrayList<T>()
            while (st.step()) out += mapper(st)
            out
        }
    }

    /** INSERT/UPDATE/DELETE 실행. 변경된 행 수(sqlite3_changes) 반환 */
    fun update(sql: String, vararg params: Any?): Int = writing {
        prepare(sql).use { st ->
            params.forEachIndexed { i, v -> st.bind(i + 1, v) }
            st.step()
            n.exports.sqlite3Changes( handle)
        }
    }

    /**
     * 트랜잭션 블록. 전체 구간 동안 write lock 을 유지한다.
     * 블록이 예외를 던지면 ROLLBACK, 정상 종료면 COMMIT.
     */
    fun <T> transaction(block: () -> T): T {
        exec("BEGIN IMMEDIATE")
        try {
            val result = block()
            exec("COMMIT")
            return result
        } catch (e: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw e
        }
    }

    /** 마지막 INSERT 의 rowid */
    fun lastInsertRowId(): Long = n.exports.sqlite3LastInsertRowid(handle)

    override fun close() {
        n.exports.sqlite3CloseV2(handle)
        n.close()
        if (holdsWrite) {
            holdsWrite = false
            fileLock?.writeLock()?.unlock()
        }
    }

    companion object {
        /** 인메모리 DB (:memory:). 커넥션마다 독립이라 공유되지 않는다. */
        fun openMemory(): SqliteDb = open(":memory:", null, null)

        /**
         * 파일 DB. [hostDir] 를 게스트의 /db 로 preopen 하고 그 안의 [fileName] 을 연다.
         * 같은 (hostDir, fileName) 을 연 커넥션들은 [DbCoordinator] 로 동시성이 조율된다.
         *
         * 멀티커넥션 동시 접근에는 롤백 저널 모드(기본/DELETE)를 사용한다.
         * WAL(다중 커넥션)은 shm 이 필요해 아직 미지원이며, 단일 커넥션 WAL 은
         * `PRAGMA locking_mode=EXCLUSIVE; PRAGMA journal_mode=WAL` 로 가능하다.
         */
        fun openFile(hostDir: Path, fileName: String): SqliteDb {
            val canonical = hostDir.toAbsolutePath().normalize().resolve(fileName).toString()
            val lock = DbCoordinator.lockFor(canonical)
            return open("/db/$fileName", hostDir, lock)
        }

        private fun open(guestPath: String, preopenHostDir: Path?, fileLock: ReentrantReadWriteLock?): SqliteDb {
            val opts = WasiOptions.builder()
                .withStdout(System.out)
                .withStderr(System.err)
            if (preopenHostDir != null) opts.withDirectory("/db", preopenHostDir)

            val n = Native(opts.build())
            val ppDb = n.malloc(4)
            val namePtr = n.cString(guestPath)
            val rc = n.exports.sqlite3Open(namePtr, ppDb)
            val db = n.readInt(ppDb)
            n.free(ppDb)
            n.free(namePtr)
            if (rc != SQLITE_OK) {
                n.close()
                error("sqlite3_open('$guestPath') rc=$rc")
            }
            // 공유 파일: SQLite 자체 락 경합(SQLITE_BUSY) 시 즉시 실패 대신 대기·재시도
            if (fileLock != null) n.exports.sqlite3BusyTimeout(db, 5000)
            return SqliteDb(n, db, fileLock)
        }
    }
}
