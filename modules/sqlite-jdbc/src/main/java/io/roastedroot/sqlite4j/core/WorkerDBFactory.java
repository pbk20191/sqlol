package io.roastedroot.sqlite4j.core;

import io.roastedroot.sqlite4j.SQLiteConfig;
import java.sql.SQLException;
import org.jspecify.annotations.NonNull;

/**
 * 벤더링 패치: 원본의 WasmDBFactory(ZeroFs 공유 FS 관리) 대체.
 * 런타임 공유/refcount 는 modules/sqlite 의 WorkerDbRuntimes 가 담당하므로 여기는 순수 팩토리.
 */
public class WorkerDBFactory {

    public WorkerDBFactory() {}

    public WorkerDB create(
            @NonNull String url, @NonNull String fileName, @NonNull SQLiteConfig config, boolean isMemory)
            throws SQLException {
        return new WorkerDB(url, fileName, config, isMemory);
    }

    public void close(@NonNull WorkerDB db) throws SQLException {
        db.close();
    }
}
