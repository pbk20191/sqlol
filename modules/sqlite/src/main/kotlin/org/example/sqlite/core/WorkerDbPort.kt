package org.example.sqlite.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
    private val hostTmp: Path,
    private val release: Runnable,
) : AutoCloseable {

    @Volatile private var closed = false

    companion object {
        /** PROTOCOL(15) 흡수 재시도 정책 — 총 ~1s (200 × 5ms). */
        private const val PROTOCOL_MAX_RETRIES = 200
        private const val PROTOCOL_RETRY_SLEEP_MS = 5L

        /** sqlite3_carray_bind 의 mFlags (SQLITE_CARRAY_*). */
        private const val CARRAY_INT64 = 1
        private const val CARRAY_DOUBLE = 2
        private const val CARRAY_TEXT = 3

        internal fun spawn(
            rt: JvmVfsRuntime,
            guestPath: String,
            spawnLock: ReentrantLock,
            readOnly: Boolean,
            hostTmp: Path,
            release: Runnable,
        ): WorkerDbPort {
            val flags = if (readOnly) SqliteWorker.OPEN_READONLY else SqliteWorker.OPEN_DEFAULT
            val worker = spawnLock.withLock { SqliteWorker.spawn(rt, guestPath, flags) }   // §9 직렬화
            return WorkerDbPort(rt, worker, hostTmp, release)
        }
    }

    /** caller 스레드 직접 실행 (per-child 락 직렬화 — §9.2). */
    private fun <T> submit(fn: (SqliteWorker.Session) -> T): T {
        check(!closed) { "port closed" }
        return worker.run(fn)
    }

    // ---- 커넥션 수준 ----

    /**
     * sqlite3_exec (다중 문장 가능). rc 반환 — 실패 시 **extended result code**
     * (예: 19 CONSTRAINT → 2067 CONSTRAINT_UNIQUE; xerial 의 정밀 에러 매핑이 기대하는 형태).
     */
    fun exec(sql: String): Int = submit { s ->
        val rc = s.exec(sql)
        if (rc == 0) 0 else s.x.sqlite3ExtendedErrcode(s.db)
    }

    /** 마지막 오류 메시지 (sqlite3_errmsg). */
    fun errmsg(): String = submit { s -> s.mem.readCString(s.x.sqlite3Errmsg(s.db)) }

    /** 실행 중 문장 즉시 중단 — 워커 큐를 **거치지 않고** main 인스턴스 경유 (블록된 워커도 풀림). */
    fun interrupt() {
        rt.interruptDb(worker.dbPtr)
    }

    fun busyTimeout(ms: Int): Int = submit { s -> s.x.sqlite3BusyTimeout(s.db, ms) }

    fun changes(): Long = submit { s -> s.x.sqlite3Changes(s.db).toLong() }

    fun totalChanges(): Long = submit { s -> s.x.sqlite3TotalChanges64(s.db) }

    fun libversion(): String = submit { s -> s.mem.readCString(s.x.sqlite3Libversion()) }

    fun limit(id: Int, value: Int): Int = submit { s -> s.x.sqlite3Limit(s.db, id, value) }

    // ---- stmt 수명 (사용자 소유 — Session.userStmts registry 가 누수/UAF 가드) ----

    /** prepare → stmt 핸들. 실패 시 예외 (errmsg 포함). */
    fun prepare(sql: String): Int = submit { s -> s.prepareUser(sql).first }

    /** finalize — 멱등. rc 0 고정 (registry 밖이면 no-op). */
    fun finalizeStmt(st: Int): Int = submit { s -> s.finalizeUser(st); 0 }

    /**
     * step — rc=15(PROTOCOL, WAL 내부 재시도 고갈)만 포트에서 흡수 (xerial 은 모르는 코드).
     * BUSY(5)는 그대로 — sqlite3_busy_timeout 의 내부 sleep 후에 도달한 값이라 JDBC 시맨틱의 몫.
     * 그 외 오류는 extended result code 로 (같은 태스크 안에서 조회 — 인터리빙 불가).
     * 재시도 소진 시에도 15 를 새지 않고 BUSY 로 반환 — xerial 이 아는 "경합 지속" 코드.
     */
    fun step(st: Int): Int {
        repeat(PROTOCOL_MAX_RETRIES) {
            val rc = submit { s ->
                when (val rc = s.x.sqlite3Step(st)) {
                    SQLITE_ROW, SQLITE_DONE, SQLITE_BUSY, SQLITE_PROTOCOL -> rc
                    else -> s.x.sqlite3ExtendedErrcode(s.db)
                }
            }
            if (rc != SQLITE_PROTOCOL) return rc
            Thread.sleep(PROTOCOL_RETRY_SLEEP_MS)
        }
        return SQLITE_BUSY
    }

    fun reset(st: Int): Int = submit { s -> s.x.sqlite3Reset(st) }

    fun clearBindings(st: Int): Int = submit { s -> s.x.sqlite3ClearBindings(st) }

    fun bindParameterCount(st: Int): Int = submit { s -> s.x.sqlite3BindParameterCount(st) }

    // ---- 컬럼 메타/값 ----

    fun columnCount(st: Int): Int = submit { s -> s.x.sqlite3ColumnCount(st) }

    fun columnType(st: Int, col: Int): Int = submit { s -> s.x.sqlite3ColumnType(st, col) }

    fun columnName(st: Int, col: Int): String? =
        submit { s -> cstrOrNull(s, s.x.sqlite3ColumnName(st, col)) }

    fun columnDecltype(st: Int, col: Int): String? =
        submit { s -> cstrOrNull(s, s.x.sqlite3ColumnDecltype(st, col)) }

    fun columnTableName(st: Int, col: Int): String? =
        submit { s -> cstrOrNull(s, s.x.sqlite3ColumnTableName(st, col)) }

    /** NULL 값이면 null (xerial NativeDB 시맨틱). 길이는 column_text **후** column_bytes (sqlite 규약). */
    fun columnText(st: Int, col: Int): String? = submit { s ->
        val p = s.x.sqlite3ColumnText(st, col)
        if (p == 0) null else s.mem.readString(p, s.x.sqlite3ColumnBytes(st, col))
    }

    fun columnBlob(st: Int, col: Int): ByteArray? = submit { s ->
        val p = s.x.sqlite3ColumnBlob(st, col)
        // 빈 blob 은 ptr 0 + type BLOB — null(=NULL 값)과 구분
        if (p == 0) {
            if (s.x.sqlite3ColumnType(st, col) == TYPE_NULL) null else ByteArray(0)
        } else s.mem.readBytes(p, s.x.sqlite3ColumnBytes(st, col))
    }

    fun columnLong(st: Int, col: Int): Long = submit { s -> s.x.sqlite3ColumnInt64(st, col) }

    fun columnInt(st: Int, col: Int): Int = submit { s -> s.x.sqlite3ColumnInt(st, col) }

    fun columnDouble(st: Int, col: Int): Double = submit { s -> s.x.sqlite3ColumnDouble(st, col) }

    /** xerial column_metadata: 컬럼별 [notnull, primarykey, autoincrement] — 한 태스크로 전 컬럼. */
    fun columnMetadata(st: Int): Array<BooleanArray> = submit { s ->
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

    fun bind(st: Int, pos: Int, v: Any?): Int = submit { s -> s.bindOne(st, pos, v) }

    // ---- carray 바인딩 (JDBC setArray 백엔드 — `IN (SELECT value FROM carray(?))`) ----
    // xDel = SQLITE_TRANSIENT(-1): sqlite3_carray_bind 가 sqlite3_malloc64 로 **딥카피**
    // (TEXT 는 문자열까지) 하므로 게스트 버퍼는 호출 직후 해제 — malloc 혼합 함정(불변식 4) 회피.

    fun bindCarrayInt64(st: Int, pos: Int, values: LongArray): Int = submit { s ->
        val bytes = java.nio.ByteBuffer.allocate(8 * values.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .apply { asLongBuffer().put(values) }.array()
        withGuestBuf(s, bytes) { buf ->
            s.x.sqlite3CarrayBind(st, pos, buf, values.size, CARRAY_INT64, SQLITE_TRANSIENT)
        }
    }

    fun bindCarrayDouble(st: Int, pos: Int, values: DoubleArray): Int = submit { s ->
        val bytes = java.nio.ByteBuffer.allocate(8 * values.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .apply { asDoubleBuffer().put(values) }.array()
        withGuestBuf(s, bytes) { buf ->
            s.x.sqlite3CarrayBind(st, pos, buf, values.size, CARRAY_DOUBLE, SQLITE_TRANSIENT)
        }
    }

    /**
     * TEXT 배열: [char* × n][NUL-종단 UTF-8 …] 레이아웃. null 원소 = NULL 포인터 → SQL NULL.
     * 임베디드 NUL 은 char* ABI 로 표현 불가(carray 가 strlen 으로 복사) — 조용한 절단 대신 즉시 거부.
     */
    fun bindCarrayText(st: Int, pos: Int, values: Array<String?>): Int {
        values.forEachIndexed { i, v ->
            require(v == null || v.indexOf('\u0000') < 0) {
                "carray TEXT element[$i] contains embedded NUL — char* ABI 로 표현 불가"
            }
        }
        return bindCarrayTextChecked(st, pos, values)
    }

    private fun bindCarrayTextChecked(st: Int, pos: Int, values: Array<String?>): Int = submit { s ->
        val encoded = values.map { it?.toByteArray(Charsets.UTF_8) }
        val ptrArea = 4 * values.size
        val total = ptrArea + encoded.sumOf { (it?.size ?: -1) + 1 }
        val buf = s.x.malloc(maxOf(total, 1))
        try {
            val ptrs = java.nio.ByteBuffer.allocate(ptrArea).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var strOff = buf + ptrArea
            for (e in encoded) {
                if (e == null) { ptrs.putInt(0); continue }
                ptrs.putInt(strOff)
                s.mem.write(strOff, e)
                s.mem.writeByte(strOff + e.size, 0)
                strOff += e.size + 1
            }
            if (values.isNotEmpty()) s.mem.write(buf, ptrs.array())
            s.x.sqlite3CarrayBind(st, pos, buf, values.size, CARRAY_TEXT, SQLITE_TRANSIENT)
        } finally {
            s.x.free(buf)
        }
    }

    /** [bytes] 를 게스트 스크래치에 쓰고 [fn] 실행 후 해제 (빈 배열도 유효 포인터 1바이트). */
    private inline fun withGuestBuf(s: SqliteWorker.Session, bytes: ByteArray, fn: (Int) -> Int): Int {
        val buf = s.x.malloc(maxOf(bytes.size, 1))
        try {
            if (bytes.isNotEmpty()) s.mem.write(buf, bytes)
            return fn(buf)
        } finally {
            s.x.free(buf)
        }
    }

    // ---- 콜백 등록 (helpers.c 테이블 슬롯 + user_data=레지스트리 키, §10 ①) ----
    // 등록 자체는 그 커넥션 db 에 대한 호출이라 워커 태스크. 콜백 "실행"은 wasm step 안에서
    // env import 로 들어온다 (JvmVfsRuntime.buildImports — 레지스트리는 [callbacks]).

    /** 콜백 레지스트리 — WorkerDB 가 어댑터를 등록하고 키를 user_data 로 넘긴다. */
    val callbacks: JvmCallbacks get() = rt.callbacks

    object FnKind { const val SCALAR = 0; const val AGGREGATE = 1; const val WINDOW = 2 }

    /** create_function_v2/window — [key] = 레지스트리 키. eTextRep 에 DETERMINISTIC 등 플래그 포함. */
    fun createFunction(name: String, nArgs: Int, eTextRep: Int, key: Int, kind: Int): Int =
        submit { s ->
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
        submit { s ->
            s.x.sqlite3CreateFunctionV2(s.db, s.sqlPtr(name), nArgs, eTextRep, 0, 0, 0, 0, 0)
        }

    fun createCollation(name: String, eTextRep: Int, key: Int): Int =
        submit { s ->
            val p = rt.cbPtrs
            s.x.sqlite3CreateCollationV2(s.db, s.sqlPtr(name), eTextRep, key, p.xCompare, p.xDestroyCollation)
        }

    fun destroyCollation(name: String, eTextRep: Int): Int =
        submit { s ->
            s.x.sqlite3CreateCollationV2(s.db, s.sqlPtr(name), eTextRep, 0, 0, 0)
        }

    /** [key] 0 = 해제. busy_timeout 과 상호 배타 (sqlite 시맨틱 — 마지막 설정이 이김). */
    fun busyHandler(key: Int): Int = submit { s ->
        val p = rt.cbPtrs
        s.x.sqlite3BusyHandler(s.db, if (key == 0) 0 else p.xBusy, key)
    }

    /** [key] 0 = 해제. */
    fun progressHandler(vmCalls: Int, key: Int): Unit = submit { s ->
        val p = rt.cbPtrs
        s.x.sqlite3ProgressHandler(s.db, vmCalls, if (key == 0) 0 else p.xProgress, key)
    }

    /** commit/rollback 훅 쌍 — 키 0 = 해제. 이전 user_data 반환은 버린다 (키 해제는 호출측). */
    fun commitHooks(commitKey: Int, rollbackKey: Int): Unit = submit { s ->
        val p = rt.cbPtrs
        s.x.sqlite3CommitHook(s.db, if (commitKey == 0) 0 else p.xCommit, commitKey)
        s.x.sqlite3RollbackHook(s.db, if (rollbackKey == 0) 0 else p.xRollback, rollbackKey)
    }

    /** [key] 0 = 해제. */
    fun updateHook(key: Int): Unit = submit { s ->
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
        destFile: Path,
        observer: BackupObserver?,
        sleepMillis: Int,
        nTimeoutLimit: Int,
        pagesPerStep: Int,
    ): Int {
        val tmpName = "backup-${System.nanoTime()}.db"
        val hostFile = hostTmp.resolve(tmpName)
        try {
            val rc = submit { s ->
                runBackup(s, srcDbName, "/tmp/$tmpName", toDest = true, observer, sleepMillis, nTimeoutLimit, pagesPerStep)
            }
            if (rc == 0) {
                // 부모 디렉터리를 만들어 주지 않는다 — sqlite3_open 시맨틱 (부모 부재 = CANTOPEN 은 호출측 매핑)
                Files.copy(hostFile, destFile, StandardCopyOption.REPLACE_EXISTING)
            }
            return rc
        } finally {
            runCatching { Files.deleteIfExists(hostFile) }
        }
    }

    /** [srcFile] 의 내용을 이 커넥션의 [destDbName] 스키마로 복원. @return rc (0=OK). */
    fun restore(
        destDbName: String,
        srcFile: Path,
        observer: BackupObserver?,
        sleepMillis: Int,
        nTimeoutLimit: Int,
        pagesPerStep: Int,
    ): Int {
        val tmpName = "restore-${System.nanoTime()}.db"
        val hostFile = hostTmp.resolve(tmpName)
        Files.copy(srcFile, hostFile, StandardCopyOption.REPLACE_EXISTING)
        try {
            return submit { s ->
                runBackup(s, destDbName, "/tmp/$tmpName", toDest = false, observer, sleepMillis, nTimeoutLimit, pagesPerStep)
            }
        } finally {
            runCatching { Files.deleteIfExists(hostFile) }
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
            val openFlags = if (toDest) SqliteWorker.OPEN_DEFAULT else SqliteWorker.OPEN_READONLY
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
                    if (observer != null && (rc == SQLITE_OK || rc == SQLITE_DONE)) {
                        observer.progress(s.x.sqlite3BackupRemaining(pBackup), s.x.sqlite3BackupPagecount(pBackup))
                    }
                    if (rc == SQLITE_BUSY || rc == SQLITE_LOCKED) {
                        if (nTimeout++ >= nTimeoutLimit) break
                        Thread.sleep(sleepMillis.toLong())
                    }
                } while (rc == SQLITE_OK || rc == SQLITE_BUSY || rc == SQLITE_LOCKED)
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
    fun serialize(schema: String): ByteArray? = submit { s ->
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
    fun deserialize(schema: String, data: ByteArray): Int = submit { s ->
        val buf = s.x.sqlite3Malloc64(maxOf(data.size, 1).toLong())
        if (buf == 0) return@submit SQLITE_NOMEM
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
