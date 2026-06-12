package io.roastedroot.sqlite4j.core;

import io.roastedroot.sqlite4j.BusyHandler;
import io.roastedroot.sqlite4j.Collation;
import io.roastedroot.sqlite4j.Function;
import io.roastedroot.sqlite4j.ProgressHandler;
import io.roastedroot.sqlite4j.SQLiteConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.example.sqlite.WorkerDbPort;

/**
 * xerial {@link DB} 절단면을 modules/sqlite 의 워커 아키텍처(wasm pthread + WAL) 위에 구현.
 *
 * <p>모든 연산은 {@link WorkerDbPort}(워커 1개 = sqlite 커넥션 1개)로 마샬링된다 — 큐가 직렬화하므로
 * 한 JDBC 커넥션 위의 동시 호출/다중 커서 인터리빙이 안전하다. 같은 파일을 여는 JDBC 커넥션들은
 * 런타임을 공유해 다중 커넥션 WAL 동시성(reader 동시 + writer 직렬)을 그대로 얻는다.
 *
 * <p>콜백 계열(UDF/콜레이션/busy_handler/progress/커밋·업데이트 훅)은 helpers.c env 테이블 +
 * user_data 레지스트리(JvmCallbacks)로, backup/restore/serialize 는 직접 API + guest "/tmp" 중계로
 * 구현 — xerial DB 표면 완전 동등 (BUILD.md §11.2/§11.3).
 */
public final class WorkerDB extends DB {

    private final boolean memory;
    private volatile WorkerDbPort port;

    public WorkerDB(String url, String fileName, SQLiteConfig config, boolean isMemory)
            throws SQLException {
        super(url, fileName, config);
        this.memory = isMemory;
    }

    /** 벤더링된 SQLiteJDBCLoader 가 드라이버 버전으로 사용 (sqlite 아말감 버전 고정). */
    public static String version() {
        return "3.53.0";
    }

    private interface Op<T> {
        T run() throws Exception;
    }

    /** 포트 호출 공통 래핑 — 워커 타임아웃/종료(IllegalState)·ExecutionException 을 SQLException 으로. */
    private <T> T call(Op<T> op) throws SQLException {
        try {
            return op.run();
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            // rc 보존 예외(open 등)는 정밀 SQLITE_* 코드로 (예: rc=14 → [SQLITE_CANTOPEN])
            Throwable rcEx = e;
            while (rcEx != null && !(rcEx instanceof org.example.sqlite.SqliteNativeException)) {
                rcEx = rcEx.getCause();
            }
            if (rcEx != null) {
                org.example.sqlite.SqliteNativeException ne =
                        (org.example.sqlite.SqliteNativeException) rcEx;
                throw DB.newSQLException(ne.getRc(), ne.getMessage());
            }
            throw new SQLException(e.getMessage(), e);
        }
    }

    private WorkerDbPort port() throws SQLException {
        WorkerDbPort p = port;
        if (p == null) throw new SQLException("database not open");
        return p;
    }

    // ---- 수명 ----

    /** SQLiteOpenMode 비트 (sqlite3_open_v2 와 동일). */
    private static final int OPEN_READONLY = 0x1;
    private static final int OPEN_CREATE = 0x4;

    @Override
    protected synchronized void _open(String filename, int openFlags) throws SQLException {
        if (port != null) throw new SQLException("already open: " + filename);
        boolean readOnly = (openFlags & OPEN_READONLY) != 0;
        if (!memory) {
            Path p = Path.of(filename);
            if (!Files.exists(p)) {
                if ((openFlags & OPEN_CREATE) == 0) {
                    throw new SQLException("Database file doesn't exists: " + filename);
                }
                Path parent = p.toAbsolutePath().getParent();
                try {
                    if (parent != null) Files.createDirectories(parent);
                } catch (Exception e) {
                    throw new SQLException("Failed to create db file: " + filename, e);
                }
            }
        }
        port =
                call(
                        () ->
                                memory
                                        ? WorkerDbPort.openMemory(readOnly)
                                        : WorkerDbPort.openFile(Path.of(filename), readOnly));
    }

    @Override
    protected synchronized void _close() throws SQLException {
        WorkerDbPort p = port;
        if (p != null) {
            port = null;
            int[] keys = {busyKey, progressKey, commitKey, rollbackKey, updateKey};
            busyKey = progressKey = commitKey = rollbackKey = updateKey = 0;
            // close 유발 롤백(열린 tx)은 리스너에 전달하지 않는다 — 업스트림 WasmDB._close 시맨틱 미러
            // (훅 자체는 fire 하지만 빈 집합 순회 = no-op)
            updateListeners.clear();
            commitListeners.clear();
            try {
                // 먼저 guest close — sqlite3_close 가 열린 트랜잭션을 롤백하며 xRollback 등 훅을
                // **정당하게** 호출할 수 있으므로, 레지스트리 키는 그때까지 살아 있어야 한다.
                // (구 큐 모델에선 이 순서 버그가 워커 VT 의 uncaughtHandler 로 조용히 삼켜졌었다 — §9.2)
                call(
                        () -> {
                            p.close();
                            return null;
                        });
            } finally {
                // 키 해제 + 0 (WasmDBHelper 가시성 계약)
                for (int k : keys) {
                    if (k != 0) p.getCallbacks().free(k);
                }
            }
        }
    }

    // ---- 커넥션 수준 ----

    @Override
    public void interrupt() throws SQLException {
        // 워커 큐를 거치지 않는 유일한 연산 — 실행 중(블록된) 문장도 풀어야 하므로
        port().interrupt();
    }

    @Override
    public void busy_timeout(int ms) throws SQLException {
        call(() -> port().busyTimeout(ms));
    }

    @Override
    public synchronized void busy_handler(BusyHandler busyHandler) throws SQLException {
        WorkerDbPort p = port();
        int old = busyKey;
        if (busyHandler == null) {
            busyKey = 0;
            call(() -> p.busyHandler(0));
        } else {
            busyKey =
                    p.getCallbacks()
                            .register(
                                    (org.example.sqlite.JvmCallbacks.Busy)
                                            nPrev -> {
                                                try {
                                                    return busyHandler.callback(nPrev);
                                                } catch (SQLException e) {
                                                    sneakyThrow(e);
                                                    return 0;
                                                }
                                            });
            int key = busyKey;
            call(() -> p.busyHandler(key));
        }
        if (old != 0) p.getCallbacks().free(old);
    }

    @Override
    String errmsg() throws SQLException {
        return call(() -> port().errmsg());
    }

    @Override
    public String libversion() throws SQLException {
        return call(() -> port().libversion());
    }

    @Override
    public long changes() throws SQLException {
        return call(() -> port().changes());
    }

    @Override
    public long total_changes() throws SQLException {
        return call(() -> port().totalChanges());
    }

    @Override
    public int shared_cache(boolean enable) throws SQLException {
        return SQLITE_OK; // 인스턴스-per-커넥션 모델엔 의미 없음 — no-op
    }

    @Override
    public int enable_load_extension(boolean enable) throws SQLException {
        return SQLITE_OK; // WASI 엔 동적 .so 없음 (정적 컴파일 확장은 전부 내장) — no-op
    }

    @Override
    public int _exec(String sql) throws SQLException {
        return call(() -> port().exec(sql));
    }

    @Override
    public int limit(int id, int value) throws SQLException {
        return call(() -> port().limit(id, value));
    }

    // ---- stmt 수명/실행 ----

    @Override
    protected SafeStmtPtr prepare(String sql) throws SQLException {
        int st = call(() -> port().prepare(sql));
        return new SafeStmtPtr(this, st);
    }

    @Override
    protected int finalize(long stmt) throws SQLException {
        return call(() -> port().finalizeStmt((int) stmt));
    }

    @Override
    public int step(long stmt) throws SQLException {
        return call(() -> port().step((int) stmt));
    }

    @Override
    public int reset(long stmt) throws SQLException {
        return call(() -> port().reset((int) stmt));
    }

    @Override
    public int clear_bindings(long stmt) throws SQLException {
        return call(() -> port().clearBindings((int) stmt));
    }

    @Override
    int bind_parameter_count(long stmt) throws SQLException {
        return call(() -> port().bindParameterCount((int) stmt));
    }

    // ---- 컬럼 ----

    @Override
    public int column_count(long stmt) throws SQLException {
        return call(() -> port().columnCount((int) stmt));
    }

    @Override
    public int column_type(long stmt, int col) throws SQLException {
        return call(() -> port().columnType((int) stmt, col));
    }

    @Override
    public String column_decltype(long stmt, int col) throws SQLException {
        return call(() -> port().columnDecltype((int) stmt, col));
    }

    @Override
    public String column_table_name(long stmt, int col) throws SQLException {
        return call(() -> port().columnTableName((int) stmt, col));
    }

    @Override
    public String column_name(long stmt, int col) throws SQLException {
        return call(() -> port().columnName((int) stmt, col));
    }

    @Override
    public String column_text(long stmt, int col) throws SQLException {
        return call(() -> port().columnText((int) stmt, col));
    }

    @Override
    public byte[] column_blob(long stmt, int col) throws SQLException {
        return call(() -> port().columnBlob((int) stmt, col));
    }

    @Override
    public double column_double(long stmt, int col) throws SQLException {
        return call(() -> port().columnDouble((int) stmt, col));
    }

    @Override
    public long column_long(long stmt, int col) throws SQLException {
        return call(() -> port().columnLong((int) stmt, col));
    }

    @Override
    public int column_int(long stmt, int col) throws SQLException {
        return call(() -> port().columnInt((int) stmt, col));
    }

    @Override
    boolean[][] column_metadata(long stmt) throws SQLException {
        return call(() -> port().columnMetadata((int) stmt));
    }

    // ---- 바인딩 (타입 디스패치/TRANSIENT/스크래치는 포트 너머 Session 공통) ----

    @Override
    int bind_null(long stmt, int pos) throws SQLException {
        return call(() -> port().bind((int) stmt, pos, null));
    }

    @Override
    int bind_int(long stmt, int pos, int v) throws SQLException {
        return call(() -> port().bind((int) stmt, pos, v));
    }

    @Override
    int bind_long(long stmt, int pos, long v) throws SQLException {
        return call(() -> port().bind((int) stmt, pos, v));
    }

    @Override
    int bind_double(long stmt, int pos, double v) throws SQLException {
        return call(() -> port().bind((int) stmt, pos, v));
    }

    @Override
    int bind_text(long stmt, int pos, String v) throws SQLException {
        return call(() -> port().bind((int) stmt, pos, v));
    }

    @Override
    int bind_blob(long stmt, int pos, byte[] v) throws SQLException {
        return call(() -> port().bind((int) stmt, pos, v));
    }

    // ---- 콜백 계열 (helpers.c env 테이블 + user_data 레지스트리 — BUILD.md §10 ①) ----
    //
    // 실행 경로: 워커 스레드의 wasm step 안에서 env import → JvmVfsRuntime 호스트 함수 →
    // JvmCallbacks 레지스트리(user_data 키) → 아래 어댑터. value_*/result_* 는 그 스레드 위의
    // 재진입 export 호출이라 **큐를 타지 않는다** (자기 큐 제출 = 데드락).

    /** SQLITE_UTF8 — 등록 인코딩 (xerial flags 와 OR). */
    private static final int UTF8 = 1;

    /** UDF 콜백이 실행 중인 동안의 호출 컨텍스트 — 이 커넥션의 워커 스레드만 만진다 (UDF 는 step 스레드 전용). */
    private volatile org.example.sqlite.CallbackEnv activeEnv;

    /** name → (registry key, nArgs) — destroy 시 같은 nArgs 로 NULL 재등록해야 정확히 교체된다. */
    private final java.util.Map<String, int[]> functionKeys = new java.util.HashMap<>();

    private final java.util.Map<String, Integer> collationKeys = new java.util.HashMap<>();
    private int busyKey;
    private int progressKey;
    private int commitKey;
    private int rollbackKey;
    private int updateKey;

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    private org.example.sqlite.CallbackEnv env() throws SQLException {
        org.example.sqlite.CallbackEnv e = activeEnv;
        if (e == null) throw new SQLException("UDF 콜백 실행 중이 아님");
        return e;
    }

    private int valuePtr(Function f, int arg) throws SQLException {
        return env().deref((int) f.getValueArg(arg));
    }

    /** xerial Function → JvmCallbacks.Udf 어댑터 (WasmDB 디스패치와 동일: context/value/args 주입). */
    private final class UdfAdapter implements org.example.sqlite.JvmCallbacks.Udf {
        private final Function f;

        UdfAdapter(Function f) {
            this.f = f;
        }

        private void invoke(org.example.sqlite.CallbackEnv env, int ctx, int argc, int argv, Runnable call) {
            activeEnv = env;
            f.setContext(ctx);
            f.setValue(argv);
            f.setArgs(argc);
            call.run();
        }

        @Override
        public void xFunc(org.example.sqlite.CallbackEnv env, int ctx, int argc, int argv) {
            invoke(env, ctx, argc, argv, () -> {
                try {
                    f.xFunc();
                } catch (SQLException e) {
                    sneakyThrow(e);
                }
            });
        }

        @Override
        public void xStep(org.example.sqlite.CallbackEnv env, int ctx, int argc, int argv) {
            invoke(env, ctx, argc, argv, () -> {
                try {
                    ((Function.Aggregate) f).xStep();
                } catch (SQLException e) {
                    sneakyThrow(e);
                }
            });
        }

        @Override
        public void xFinal(org.example.sqlite.CallbackEnv env, int ctx) {
            activeEnv = env;
            f.setContext(ctx);
            try {
                ((Function.Aggregate) f).xFinal();
            } catch (SQLException e) {
                sneakyThrow(e);
            }
        }

        @Override
        public void xValue(org.example.sqlite.CallbackEnv env, int ctx) {
            activeEnv = env;
            f.setContext(ctx);
            try {
                ((Function.Window) f).xValue();
            } catch (SQLException e) {
                sneakyThrow(e);
            }
        }

        @Override
        public void xInverse(org.example.sqlite.CallbackEnv env, int ctx, int argc, int argv) {
            invoke(env, ctx, argc, argv, () -> {
                try {
                    ((Function.Window) f).xInverse();
                } catch (SQLException e) {
                    sneakyThrow(e);
                }
            });
        }
    }

    @Override
    public void result_null(long context) throws SQLException {
        env().resultNull((int) context);
    }

    @Override
    public void result_text(long context, String val) throws SQLException {
        env().resultText((int) context, val);
    }

    @Override
    public void result_blob(long context, byte[] val) throws SQLException {
        env().resultBlob((int) context, val);
    }

    @Override
    public void result_double(long context, double val) throws SQLException {
        env().resultDouble((int) context, val);
    }

    @Override
    public void result_long(long context, long val) throws SQLException {
        env().resultLong((int) context, val);
    }

    @Override
    public void result_int(long context, int val) throws SQLException {
        env().resultLong((int) context, val);
    }

    @Override
    public void result_error(long context, String err) throws SQLException {
        env().resultError((int) context, err);
    }

    @Override
    public String value_text(Function f, int arg) throws SQLException {
        return env().valueText(valuePtr(f, arg));
    }

    @Override
    public byte[] value_blob(Function f, int arg) throws SQLException {
        return env().valueBlob(valuePtr(f, arg));
    }

    @Override
    public double value_double(Function f, int arg) throws SQLException {
        return env().valueDouble(valuePtr(f, arg));
    }

    @Override
    public long value_long(Function f, int arg) throws SQLException {
        return env().valueLong(valuePtr(f, arg));
    }

    @Override
    public int value_int(Function f, int arg) throws SQLException {
        return env().valueInt(valuePtr(f, arg));
    }

    @Override
    public int value_type(Function f, int arg) throws SQLException {
        return env().valueType(valuePtr(f, arg));
    }

    @Override
    public synchronized int create_function(String name, Function f, int nArgs, int flags)
            throws SQLException {
        WorkerDbPort p = port();
        int kind =
                (f instanceof Function.Window)
                        ? WorkerDbPort.FnKind.WINDOW
                        : (f instanceof Function.Aggregate)
                                ? WorkerDbPort.FnKind.AGGREGATE
                                : WorkerDbPort.FnKind.SCALAR;
        int key = p.getCallbacks().register(new UdfAdapter(f));
        int rc = call(() -> p.createFunction(name, nArgs, UTF8 | flags, key, kind));
        if (rc == SQLITE_OK) {
            functionKeys.put(name, new int[] {key, nArgs});
        } else {
            p.getCallbacks().free(key);
        }
        return rc;
    }

    @Override
    public synchronized int destroy_function(String name) throws SQLException {
        WorkerDbPort p = port();
        int[] meta = functionKeys.remove(name);
        int nArgs = (meta != null) ? meta[1] : 0;
        // 같은 name+nArgs 의 NULL 재등록 → sqlite 가 xDestroy(key) 호출 → 레지스트리 자동 해제
        return call(() -> p.destroyFunction(name, nArgs, UTF8));
    }

    @Override
    public synchronized int create_collation(String name, Collation c) throws SQLException {
        WorkerDbPort p = port();
        int key =
                p.getCallbacks()
                        .register(
                                (org.example.sqlite.JvmCallbacks.Collation)
                                        (a, b) ->
                                                c.xCompare(
                                                        new String(a, java.nio.charset.StandardCharsets.UTF_8),
                                                        new String(b, java.nio.charset.StandardCharsets.UTF_8)));
        int rc = call(() -> p.createCollation(name, UTF8, key));
        if (rc == SQLITE_OK) {
            collationKeys.put(name, key);
        } else {
            p.getCallbacks().free(key);
        }
        return rc;
    }

    @Override
    public synchronized int destroy_collation(String name) throws SQLException {
        WorkerDbPort p = port();
        collationKeys.remove(name);
        // NULL 재등록 → xDestroyCollation(oldKey) → 레지스트리 자동 해제
        return call(() -> p.destroyCollation(name, UTF8));
    }

    // WasmDB 기본값 미러
    private static final int DEFAULT_BACKUP_BUSY_SLEEP_TIME_MILLIS = 100;
    private static final int DEFAULT_BACKUP_NUM_BUSY_BEFORE_FAIL = 3;
    private static final int DEFAULT_PAGES_PER_BACKUP_STEP = 100;

    @Override
    public int backup(String dbName, String destFileName, ProgressObserver observer)
            throws SQLException {
        return backup(
                dbName,
                destFileName,
                observer,
                DEFAULT_BACKUP_BUSY_SLEEP_TIME_MILLIS,
                DEFAULT_BACKUP_NUM_BUSY_BEFORE_FAIL,
                DEFAULT_PAGES_PER_BACKUP_STEP);
    }

    @Override
    public int backup(
            String dbName,
            String destFileName,
            ProgressObserver observer,
            int sleepTimeMillis,
            int nTimeouts,
            int pagesPerStep)
            throws SQLException {
        WorkerDbPort p = port();
        // sqlite3_open 시맨틱: 대상의 부모 디렉터리가 없으면 CANTOPEN (만들어 주지 않는다)
        Path destPath = Path.of(destFileName);
        Path destParent = destPath.toAbsolutePath().getParent();
        if (destParent == null || !Files.isDirectory(destParent)) {
            throw newSQLException(SQLITE_CANTOPEN, "unable to open database file: " + destFileName);
        }
        return call(
                () ->
                        p.backup(
                                dbName,
                                destPath,
                                (observer == null) ? null : observer::progress,
                                sleepTimeMillis,
                                nTimeouts,
                                pagesPerStep));
    }

    @Override
    public int restore(String dbName, String sourceFileName, ProgressObserver observer)
            throws SQLException {
        return restore(
                dbName,
                sourceFileName,
                observer,
                DEFAULT_BACKUP_BUSY_SLEEP_TIME_MILLIS,
                DEFAULT_BACKUP_NUM_BUSY_BEFORE_FAIL,
                DEFAULT_PAGES_PER_BACKUP_STEP);
    }

    @Override
    public int restore(
            String dbName,
            String sourceFileName,
            ProgressObserver observer,
            int sleepTimeMillis,
            int nTimeouts,
            int pagesPerStep)
            throws SQLException {
        WorkerDbPort p = port();
        // 복원 소스가 없으면 CANTOPEN — 파일을 만들어선 안 된다 (sqlite3_open READONLY 시맨틱)
        Path srcPath = Path.of(sourceFileName);
        if (!Files.isReadable(srcPath)) {
            throw newSQLException(SQLITE_CANTOPEN, "unable to open database file: " + sourceFileName);
        }
        return call(
                () ->
                        p.restore(
                                dbName,
                                srcPath,
                                (observer == null) ? null : observer::progress,
                                sleepTimeMillis,
                                nTimeouts,
                                pagesPerStep));
    }

    @Override
    public synchronized void register_progress_handler(int vmCalls, ProgressHandler progressHandler)
            throws SQLException {
        if (progressHandler == null) {   // ProgressHandler.setHandler(conn, n, null) = 해제
            clear_progress_handler();
            return;
        }
        WorkerDbPort p = port();
        int old = progressKey;
        progressKey =
                p.getCallbacks()
                        .register(
                                (org.example.sqlite.JvmCallbacks.Progress)
                                        () -> {
                                            try {
                                                return progressHandler.progress();
                                            } catch (SQLException e) {
                                                sneakyThrow(e);
                                                return 0;
                                            }
                                        });
        int key = progressKey;
        call(() -> { p.progressHandler(vmCalls, key); return null; });
        if (old != 0) p.getCallbacks().free(old);
    }

    @Override
    public synchronized void clear_progress_handler() throws SQLException {
        WorkerDbPort p = port();
        int old = progressKey;
        progressKey = 0;
        call(() -> { p.progressHandler(0, 0); return null; });
        if (old != 0) p.getCallbacks().free(old);
    }

    @Override
    synchronized void set_commit_listener(boolean enabled) {
        try {
            WorkerDbPort p = port();
            if (enabled) {
                commitKey =
                        p.getCallbacks()
                                .register(
                                        (org.example.sqlite.JvmCallbacks.Commit)
                                                () -> {
                                                    onCommit(true);
                                                    return 0;
                                                });
                rollbackKey =
                        p.getCallbacks()
                                .register((org.example.sqlite.JvmCallbacks.Rollback) () -> onCommit(false));
                int ck = commitKey;
                int rk = rollbackKey;
                call(() -> { p.commitHooks(ck, rk); return null; });
            } else {
                int ck = commitKey;
                int rk = rollbackKey;
                commitKey = 0;
                rollbackKey = 0;
                call(() -> { p.commitHooks(0, 0); return null; });
                if (ck != 0) p.getCallbacks().free(ck);
                if (rk != 0) p.getCallbacks().free(rk);
            }
        } catch (SQLException e) {
            sneakyThrow(e);
        }
    }

    @Override
    synchronized void set_update_listener(boolean enabled) {
        try {
            WorkerDbPort p = port();
            if (enabled) {
                updateKey =
                        p.getCallbacks()
                                .register(
                                        (org.example.sqlite.JvmCallbacks.Update)
                                                (op, dbName, table, rowId) ->
                                                        onUpdate(op, dbName, table, rowId));
                int key = updateKey;
                call(() -> { p.updateHook(key); return null; });
            } else {
                int key = updateKey;
                updateKey = 0;
                call(() -> { p.updateHook(0); return null; });
                if (key != 0) p.getCallbacks().free(key);
            }
        } catch (SQLException e) {
            sneakyThrow(e);
        }
    }

    // ---- 테스트 가시성 (코퍼스 WasmDBHelper 대응): 현재 등록된 훅의 레지스트리 키 (0 = 없음) ----

    long getProgressHandler() {
        return progressKey;
    }

    long getBusyHandler() {
        return busyKey;
    }

    long getCommitListener() {
        return commitKey;
    }

    long getUpdateListener() {
        return updateKey;
    }

    @Override
    public byte[] serialize(String schema) throws SQLException {
        byte[] result = call(() -> port().serialize(schema));
        if (result == null) {
            throw new SQLException("serialize 실패: schema=" + schema);
        }
        return result;
    }

    @Override
    public void deserialize(String schema, byte[] buff) throws SQLException {
        int rc = call(() -> port().deserialize(schema, buff));
        if (rc != SQLITE_OK) {
            throw newSQLException(rc, errmsg());
        }
    }
}
