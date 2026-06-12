package org.example.sqlite

import java.nio.file.Path
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * xerial(sqlite4j) `DB` 절단면용 **공개 포트** — JDBC 어댑터(modules/sqlite-jdbc 의 `WorkerDB`)가
 * 워커에게 raw stmt 단위 연산을 보내는 통로. 포트 1개 = 워커 1개 = sqlite 커넥션 1개.
 *
 * 의도적으로 **연산 단위가 잘다** (step 1회/column 1개 = 태스크 1건): JDBC 는 한 커넥션에 열린
 * ResultSet 여러 개가 교차로 step 할 수 있어, 코어스닝은 여기가 아니라 상위(ResultSet) 최적화로
 * 들어가야 한다. 큐가 연산을 직렬화하므로 JDBC 쪽 동시 호출도 안전하다.
 *
 * 런타임 공유: 같은 파일은 [WorkerDbRuntimes] 가 런타임 1개를 refcount 로 공유 (DbOwnerLock 의
 * "파일당 런타임 1개" 불변식과 양립). `:memory:` 는 글로벌 런타임 1개 위에 워커별 사유 DB
 * (xerial 의 ":memory: 는 커넥션별 분리" 시맨틱과 일치).
 */
class WorkerDbPort private constructor(
    private val rt: JvmVfsRuntime,
    private val worker: SqliteWorker,
    /** 런타임의 guest "/tmp" 가 가리키는 호스트 디렉터리 — backup/restore 의 중계 지점. */
    private val hostTmp: java.nio.file.Path,
    private val release: Runnable,
) : AutoCloseable {

    @Volatile private var closed = false

    companion object {
        /** 파일 DB 포트 — [path] 는 절대 경로. 같은 파일의 포트들은 런타임을 공유한다. */
        @JvmStatic
        @JvmOverloads
        fun openFile(path: Path, readOnly: Boolean = false): WorkerDbPort =
            WorkerDbRuntimes.openFile(path.toAbsolutePath().normalize(), readOnly)

        /** `:memory:` 포트 — 워커별 사유 인메모리 DB (커넥션 간 비공유, xerial 시맨틱). */
        @JvmStatic
        @JvmOverloads
        fun openMemory(readOnly: Boolean = false): WorkerDbPort = WorkerDbRuntimes.openMemory(readOnly)

        internal fun spawn(
            rt: JvmVfsRuntime,
            guestPath: String,
            spawnLock: ReentrantLock,
            readOnly: Boolean,
            hostTmp: java.nio.file.Path,
            release: Runnable,
        ): WorkerDbPort {
            val flags = if (readOnly) SqliteWorker.OPEN_READONLY else SqliteWorker.OPEN_DEFAULT
            val worker = spawnLock.withLock { SqliteWorker.spawn(rt, guestPath, flags) }   // §9 직렬화
            return WorkerDbPort(rt, worker, hostTmp, release)
        }
    }

    /** caller 스레드 직접 실행 (per-child 락 직렬화 — §9.2). [label] 은 진단용 잔재. */
    private fun <T> submit(label: String, fn: (SqliteWorker.Session) -> T): T {
        check(!closed) { "port closed" }
        return worker.run(fn)
    }

    // ---- 커넥션 수준 ----

    /**
     * sqlite3_exec (다중 문장 가능). rc 반환 — 실패 시 **extended result code**
     * (예: 19 CONSTRAINT → 2067 CONSTRAINT_UNIQUE; xerial 의 정밀 에러 매핑이 기대하는 형태).
     */
    fun exec(sql: String): Int = submit(sql) { s ->
        val rc = s.exec(sql)
        if (rc == 0) 0 else s.x.sqlite3ExtendedErrcode(s.db)
    }

    /** 마지막 오류 메시지 (sqlite3_errmsg). */
    fun errmsg(): String = submit("errmsg") { s -> s.mem.readCString(s.x.sqlite3Errmsg(s.db)) }

    /** 실행 중 문장 즉시 중단 — 워커 큐를 **거치지 않고** main 인스턴스 경유 (블록된 워커도 풀림). */
    fun interrupt() {
        rt.interruptDb(worker.dbPtr)
    }

    fun busyTimeout(ms: Int): Int = submit("busy_timeout") { s -> s.x.sqlite3BusyTimeout(s.db, ms) }

    fun changes(): Long = submit("changes") { s -> s.x.sqlite3Changes(s.db).toLong() }

    fun totalChanges(): Long = submit("total_changes") { s -> s.x.sqlite3TotalChanges64(s.db) }

    fun libversion(): String = submit("libversion") { s -> s.mem.readCString(s.x.sqlite3Libversion()) }

    fun limit(id: Int, value: Int): Int = submit("limit") { s -> s.x.sqlite3Limit(s.db, id, value) }

    // ---- stmt 수명 (사용자 소유 — Session.userStmts registry 가 누수/UAF 가드) ----

    /** prepare → stmt 핸들. 실패 시 예외 (errmsg 포함). */
    fun prepare(sql: String): Int = submit("prepare:$sql") { s -> s.prepareUser(sql).first }

    /** finalize — 멱등. rc 0 고정 (registry 밖이면 no-op). */
    fun finalizeStmt(st: Int): Int = submit("finalize") { s -> s.finalizeUser(st); 0 }

    /**
     * step — rc=15(PROTOCOL, WAL 내부 재시도 고갈)만 포트에서 흡수 (xerial 은 모르는 코드).
     * BUSY(5)는 그대로 — sqlite3_busy_timeout 의 내부 sleep 후에 도달한 값이라 JDBC 시맨틱의 몫.
     * 그 외 오류는 extended result code 로 (같은 태스크 안에서 조회 — 인터리빙 불가).
     */
    fun step(st: Int): Int {
        repeat(200) {
            val rc = submit("step") { s ->
                when (val rc = s.x.sqlite3Step(st)) {
                    100, 101, 5, 15 -> rc
                    else -> s.x.sqlite3ExtendedErrcode(s.db)
                }
            }
            if (rc != 15) return rc
            Thread.sleep(5)
        }
        return 15
    }

    fun reset(st: Int): Int = submit("reset") { s -> s.x.sqlite3Reset(st) }

    fun clearBindings(st: Int): Int = submit("clear_bindings") { s -> s.x.sqlite3ClearBindings(st) }

    fun bindParameterCount(st: Int): Int = submit("bind_parameter_count") { s -> s.x.sqlite3BindParameterCount(st) }

    // ---- 컬럼 메타/값 ----

    fun columnCount(st: Int): Int = submit("column_count") { s -> s.x.sqlite3ColumnCount(st) }

    fun columnType(st: Int, col: Int): Int = submit("column_type") { s -> s.x.sqlite3ColumnType(st, col) }

    fun columnName(st: Int, col: Int): String? =
        submit("column_name") { s -> cstrOrNull(s, s.x.sqlite3ColumnName(st, col)) }

    fun columnDecltype(st: Int, col: Int): String? =
        submit("column_decltype") { s -> cstrOrNull(s, s.x.sqlite3ColumnDecltype(st, col)) }

    fun columnTableName(st: Int, col: Int): String? =
        submit("column_table_name") { s -> cstrOrNull(s, s.x.sqlite3ColumnTableName(st, col)) }

    /** NULL 값이면 null (xerial NativeDB 시맨틱). 길이는 column_text **후** column_bytes (sqlite 규약). */
    fun columnText(st: Int, col: Int): String? = submit("column_text") { s ->
        val p = s.x.sqlite3ColumnText(st, col)
        if (p == 0) null else s.mem.readString(p, s.x.sqlite3ColumnBytes(st, col))
    }

    fun columnBlob(st: Int, col: Int): ByteArray? = submit("column_blob") { s ->
        val p = s.x.sqlite3ColumnBlob(st, col)
        // 빈 blob 은 ptr 0 + type BLOB — null(=NULL 값)과 구분
        if (p == 0) {
            if (s.x.sqlite3ColumnType(st, col) == 5) null else ByteArray(0)
        } else s.mem.readBytes(p, s.x.sqlite3ColumnBytes(st, col))
    }

    fun columnLong(st: Int, col: Int): Long = submit("column_long") { s -> s.x.sqlite3ColumnInt64(st, col) }

    fun columnInt(st: Int, col: Int): Int = submit("column_int") { s -> s.x.sqlite3ColumnInt(st, col) }

    fun columnDouble(st: Int, col: Int): Double = submit("column_double") { s -> s.x.sqlite3ColumnDouble(st, col) }

    /** xerial column_metadata: 컬럼별 [notnull, primarykey, autoincrement] — 한 태스크로 전 컬럼. */
    fun columnMetadata(st: Int): Array<BooleanArray> = submit("column_metadata") { s ->
        val n = s.x.sqlite3ColumnCount(st)
        val out = s.x.malloc(12)   // notnull/pk/autoinc 3 슬롯
        try {
            Array(n) { col ->
                val tbl = s.x.sqlite3ColumnTableName(st, col)
                val name = s.x.sqlite3ColumnName(st, col)
                if (tbl == 0 || name == 0) return@Array BooleanArray(3)
                val rc = s.x.sqlite3TableColumnMetadata(s.db, 0, tbl, name, 0, 0, out, out + 4, out + 8)
                if (rc != 0) BooleanArray(3)
                else BooleanArray(3) { i -> s.mem.readInt(out + i * 4) != 0 }
            }
        } finally {
            s.x.free(out)
        }
    }

    // ---- 바인딩 (타입 디스패치는 Session.bindOne — TRANSIENT + 워커 스크래치) ----

    fun bind(st: Int, pos: Int, v: Any?): Int = submit("bind") { s -> s.bindOne(st, pos, v) }

    // ---- 콜백 등록 (helpers.c 테이블 슬롯 + user_data=레지스트리 키, §10 ①) ----
    // 등록 자체는 그 커넥션 db 에 대한 호출이라 워커 태스크. 콜백 "실행"은 wasm step 안에서
    // env import 로 들어온다 (JvmVfsRuntime.buildImports — 레지스트리는 [callbacks]).

    /** 콜백 레지스트리 — WorkerDB 가 어댑터를 등록하고 키를 user_data 로 넘긴다. */
    val callbacks: JvmCallbacks get() = rt.callbacks

    object FnKind { const val SCALAR = 0; const val AGGREGATE = 1; const val WINDOW = 2 }

    /** create_function_v2/window — [key] = 레지스트리 키. eTextRep 에 DETERMINISTIC 등 플래그 포함. */
    fun createFunction(name: String, nArgs: Int, eTextRep: Int, key: Int, kind: Int): Int =
        submit("create_function:$name") { s ->
            val p = rt.cbPtrs
            when (kind) {
                FnKind.SCALAR -> s.x.sqlite3CreateFunctionV2(
                    s.db, s.sqlPtr(name), nArgs, eTextRep, key, p.xFunc, 0, 0, p.xDestroy)
                FnKind.AGGREGATE -> s.x.sqlite3CreateWindowFunction(
                    s.db, s.sqlPtr(name), nArgs, eTextRep, key, p.xStep, p.xFinal, 0, 0, p.xDestroy)
                else -> s.x.sqlite3CreateWindowFunction(
                    s.db, s.sqlPtr(name), nArgs, eTextRep, key, p.xStep, p.xFinal, p.xValue, p.xInverse, p.xDestroy)
            }
        }

    /** 같은 이름을 NULL 로 재등록 → sqlite 가 xDestroy(key) 호출 → 레지스트리 자동 해제. */
    fun destroyFunction(name: String, nArgs: Int, eTextRep: Int): Int =
        submit("destroy_function:$name") { s ->
            s.x.sqlite3CreateFunctionV2(s.db, s.sqlPtr(name), nArgs, eTextRep, 0, 0, 0, 0, 0)
        }

    fun createCollation(name: String, eTextRep: Int, key: Int): Int =
        submit("create_collation:$name") { s ->
            val p = rt.cbPtrs
            s.x.sqlite3CreateCollationV2(s.db, s.sqlPtr(name), eTextRep, key, p.xCompare, p.xDestroyCollation)
        }

    fun destroyCollation(name: String, eTextRep: Int): Int =
        submit("destroy_collation:$name") { s ->
            s.x.sqlite3CreateCollationV2(s.db, s.sqlPtr(name), eTextRep, 0, 0, 0)
        }

    /** [key] 0 = 해제. busy_timeout 과 상호 배타 (sqlite 시맨틱 — 마지막 설정이 이김). */
    fun busyHandler(key: Int): Int = submit("busy_handler") { s ->
        val p = rt.cbPtrs
        s.x.sqlite3BusyHandler(s.db, if (key == 0) 0 else p.xBusy, key)
    }

    /** [key] 0 = 해제. */
    fun progressHandler(vmCalls: Int, key: Int): Unit = submit("progress_handler") { s ->
        val p = rt.cbPtrs
        s.x.sqlite3ProgressHandler(s.db, vmCalls, if (key == 0) 0 else p.xProgress, key)
    }

    /** commit/rollback 훅 쌍 — 키 0 = 해제. 이전 user_data 반환은 버린다 (키 해제는 호출측). */
    fun commitHooks(commitKey: Int, rollbackKey: Int): Unit = submit("commit_hooks") { s ->
        val p = rt.cbPtrs
        s.x.sqlite3CommitHook(s.db, if (commitKey == 0) 0 else p.xCommit, commitKey)
        s.x.sqlite3RollbackHook(s.db, if (rollbackKey == 0) 0 else p.xRollback, rollbackKey)
    }

    /** [key] 0 = 해제. */
    fun updateHook(key: Int): Unit = submit("update_hook") { s ->
        val p = rt.cbPtrs
        s.x.sqlite3UpdateHook(s.db, if (key == 0) 0 else p.xUpdate, key)
    }

    // ---- backup / restore / serialize (직접 API — 콜백 불필요) ----
    // 파일 중계: 게스트는 런타임 preopen 밖을 못 보므로, guest "/tmp"(=[hostTmp])에 쓰고 호스트가
    // 최종 목적지로 복사한다 (sqlite4j 가 ZeroFs↔호스트로 하던 것과 동형). 주의: 한 태스크라
    // 30s watchdog 안에 끝나야 함 — 대형 DB 백업은 추후 분할 step 으로.

    /** backup/restore 진행 관찰자 (xerial ProgressObserver 대응 — Java SAM 호환). */
    fun interface BackupObserver {
        fun progress(remaining: Int, pageCount: Int)
    }

    /** [srcDbName] 스키마를 [destFile] 로 백업. @return rc (0=OK) — 실패 시 dest 미생성. */
    fun backup(
        srcDbName: String,
        destFile: java.nio.file.Path,
        observer: BackupObserver?,
        sleepMillis: Int,
        nTimeoutLimit: Int,
        pagesPerStep: Int,
    ): Int {
        val tmpName = "backup-${System.nanoTime()}.db"
        val hostFile = hostTmp.resolve(tmpName)
        try {
            val rc = submit("backup:$srcDbName") { s ->
                runBackup(s, srcDbName, "/tmp/$tmpName", toDest = true, observer, sleepMillis, nTimeoutLimit, pagesPerStep)
            }
            if (rc == 0) {
                // 부모 디렉터리를 만들어 주지 않는다 — sqlite3_open 시맨틱 (부모 부재 = CANTOPEN 은 호출측 매핑)
                java.nio.file.Files.copy(hostFile, destFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            return rc
        } finally {
            runCatching { java.nio.file.Files.deleteIfExists(hostFile) }
        }
    }

    /** [srcFile] 의 내용을 이 커넥션의 [destDbName] 스키마로 복원. @return rc (0=OK). */
    fun restore(
        destDbName: String,
        srcFile: java.nio.file.Path,
        observer: BackupObserver?,
        sleepMillis: Int,
        nTimeoutLimit: Int,
        pagesPerStep: Int,
    ): Int {
        val tmpName = "restore-${System.nanoTime()}.db"
        val hostFile = hostTmp.resolve(tmpName)
        java.nio.file.Files.copy(srcFile, hostFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        try {
            return submit("restore:$destDbName") { s ->
                runBackup(s, destDbName, "/tmp/$tmpName", toDest = false, observer, sleepMillis, nTimeoutLimit, pagesPerStep)
            }
        } finally {
            runCatching { java.nio.file.Files.deleteIfExists(hostFile) }
        }
    }

    /** sqlite 공식 백업 루프 (WasmDB 미러) — [toDest] true: s.db→파일, false: 파일→s.db. */
    private fun runBackup(
        s: SqliteWorker.Session,
        schemaName: String,
        guestPath: String,
        toDest: Boolean,
        observer: BackupObserver?,
        sleepMillis: Int,
        nTimeoutLimit: Int,
        pagesPerStep: Int,
    ): Int {
        val pp = s.x.malloc(8)
        val mainPtr = s.x.malloc(8)   // "main\0" — sqlPtr 버퍼는 schemaName 이 점유
        try {
            s.mem.write(mainPtr, "main".toByteArray())
            s.mem.writeByte(mainPtr + 4, 0)
            val openFlags = if (toDest) 6 else 1   // dest: RW|CREATE / src: READONLY
            var rc = s.x.sqlite3OpenV2(s.sqlPtr(guestPath), pp, openFlags, 0)
            val fileDb = s.mem.readInt(pp)
            if (rc != 0) {
                s.x.sqlite3CloseV2(fileDb)
                return rc
            }
            try {
                val schemaPtr = s.sqlPtr(schemaName)
                val pBackup = if (toDest) {
                    s.x.sqlite3BackupInit(fileDb, mainPtr, s.db, schemaPtr)
                } else {
                    s.x.sqlite3BackupInit(s.db, schemaPtr, fileDb, mainPtr)
                }
                if (pBackup == 0) return s.x.sqlite3ExtendedErrcode(if (toDest) fileDb else s.db)
                var nTimeout = 0
                do {
                    rc = s.x.sqlite3BackupStep(pBackup, pagesPerStep)
                    if (observer != null && (rc == 0 || rc == 101)) {
                        observer.progress(s.x.sqlite3BackupRemaining(pBackup), s.x.sqlite3BackupPagecount(pBackup))
                    }
                    if (rc == 5 || rc == 6) {   // BUSY/LOCKED
                        if (nTimeout++ >= nTimeoutLimit) break
                        Thread.sleep(sleepMillis.toLong())
                    }
                } while (rc == 0 || rc == 5 || rc == 6)
                s.x.sqlite3BackupFinish(pBackup)
                val frc = s.x.sqlite3ExtendedErrcode(if (toDest) fileDb else s.db)
                if (toDest && frc == 0) {
                    // 우리 워커는 소스를 WAL 로 강제 → backup 이 헤더까지 복사해 dest 도 WAL 플래그가
                    // 남는다. WAL 이미지는 sqlite3_deserialize 불가 + 외부 호환 ↓ — DELETE 저널로 변환.
                    s.x.sqlite3Exec(fileDb, s.sqlPtr("PRAGMA journal_mode=DELETE"), 0, 0, 0)
                }
                return frc
            } finally {
                s.x.sqlite3CloseV2(fileDb)
            }
        } finally {
            s.x.free(pp)
            s.x.free(mainPtr)
        }
    }

    /** sqlite3_serialize — [schema] 의 전체 DB 이미지. 실패 시 null. */
    fun serialize(schema: String): ByteArray? = submit("serialize:$schema") { s ->
        val sizePtr = s.x.malloc(8)
        try {
            var needFree = false
            var buf = s.x.sqlite3Serialize(s.db, s.sqlPtr(schema), sizePtr, 1 /*NOCOPY*/)
            if (buf == 0) {
                buf = s.x.sqlite3Serialize(s.db, s.sqlPtr(schema), sizePtr, 0)
                needFree = true
            }
            if (buf == 0) null
            else try {
                val n = s.mem.readLong(sizePtr)
                check(n in 0..Int.MAX_VALUE.toLong()) { "serialize 크기 초과: $n" }
                s.mem.readBytes(buf, n.toInt())
            } finally {
                // sqlite3_malloc 버퍼는 반드시 sqlite3_free 로 — libc free 와 섞으면 힙 오염
                // (sqlite3_malloc 은 빌드에 따라 8바이트 크기 헤더를 앞에 둔다)
                if (needFree) s.x.sqlite3Free(buf)
            }
        } finally {
            s.x.free(sizePtr)
        }
    }

    /**
     * sqlite3_deserialize — FREEONCLOSE|RESIZEABLE(3): 버퍼 소유권은 sqlite (실패 시에도 해제 보장).
     * 버퍼는 **sqlite3_malloc64** 로 — sqlite 가 sqlite3_free/realloc 으로 다루므로 libc malloc 과
     * 섞으면 (크기 헤더 불일치로) buf-8 해제 = 힙 메타데이터 오염 → 이후 dlmalloc 무한 스핀/유령 락
     * (§11.3 — SerializeTest 행 디버깅의 근원이었음).
     */
    fun deserialize(schema: String, data: ByteArray): Int = submit("deserialize:$schema") { s ->
        val buf = s.x.sqlite3Malloc64(maxOf(data.size, 1).toLong())
        if (buf == 0) return@submit 7   // SQLITE_NOMEM
        s.mem.write(buf, data)
        s.x.sqlite3Deserialize(s.db, s.sqlPtr(schema), buf, data.size.toLong(), data.size.toLong(), 3)
    }

    private fun cstrOrNull(s: SqliteWorker.Session, p: Int): String? =
        if (p == 0) null else s.mem.readCString(p)

    /** 워커 종료(in-flight 는 락 대기) 후 런타임 ref 반납. 멱등. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            worker.stop()
        } finally {
            release.run()
        }
    }
}

/**
 * [WorkerDbPort] 의 런타임 공유 레지스트리.
 *  - 파일: canonical 경로별 런타임 1개 (refcount) — [DbOwnerLock] 의 "파일당 런타임 1개" 강제와 양립.
 *    같은 JVM 의 JDBC 커넥션들이 같은 파일을 열면 **자동으로 워커들로 합류** (다중 커넥션 WAL).
 *  - `:memory:`: 글로벌 런타임 1개 (lazy, refcount) — 워커마다 사유 인메모리 DB 라 공유 없음.
 */
internal object WorkerDbRuntimes {
    private class FileEntry(val handle: SqliteDataSource.RuntimeHandle) {
        var refs = 0
        val spawnLock = ReentrantLock()
    }

    private val files = HashMap<Path, FileEntry>()
    private var memRt: JvmVfsRuntime? = null
    private var memTmp: Path? = null
    private var memRefs = 0
    private val memSpawnLock = ReentrantLock()

    @Synchronized
    fun openFile(path: Path, readOnly: Boolean): WorkerDbPort {
        val dir = path.parent ?: error("절대 경로 필요: $path")
        val entry = files.getOrPut(path) {
            FileEntry(SqliteDataSource.openRuntime(dir, path.fileName.toString()))
        }
        entry.refs++
        try {
            // openRuntime 이 "/tmp" 를 hostDir/.tmp 로 preopen 함 (sorter 스필과 공유)
            return WorkerDbPort.spawn(
                entry.handle.rt, entry.handle.guestPath, entry.spawnLock, readOnly, dir.resolve(".tmp")
            ) { releaseFile(path) }
        } catch (t: Throwable) {
            releaseFile(path)
            throw t
        }
    }

    @Synchronized
    private fun releaseFile(path: Path) {
        val entry = files[path] ?: return
        if (--entry.refs <= 0) {
            files.remove(path)
            try {
                entry.handle.rt.close()
            } finally {
                entry.handle.ownerLock.close()
            }
        }
    }

    @Synchronized
    fun openMemory(readOnly: Boolean): WorkerDbPort {
        val rt = memRt ?: run {
            // backup/restore 중계용 "/tmp" — :memory: 도 파일 백업이 가능해야 한다 (xerial 시맨틱)
            val tmp = java.nio.file.Files.createTempDirectory("sqlite-jvm-mem")
            memTmp = tmp
            JvmVfsRuntime(mapOf("/tmp" to tmp)).also { memRt = it }
        }
        memRefs++
        try {
            return WorkerDbPort.spawn(rt, ":memory:", memSpawnLock, readOnly, memTmp!!) { releaseMemory() }
        } catch (t: Throwable) {
            releaseMemory()
            throw t
        }
    }

    @Synchronized
    private fun releaseMemory() {
        if (--memRefs <= 0) {
            memRt?.close()
            memRt = null
            memTmp?.let { tmp ->
                runCatching {
                    java.nio.file.Files.list(tmp).use { l -> l.forEach { java.nio.file.Files.deleteIfExists(it) } }
                    java.nio.file.Files.deleteIfExists(tmp)
                }
            }
            memTmp = null
        }
    }
}
