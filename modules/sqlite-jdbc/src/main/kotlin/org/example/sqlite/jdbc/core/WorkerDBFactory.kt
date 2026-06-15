package org.example.sqlite.jdbc.core

import org.example.sqlite.jdbc.SQLiteConfig
import java.sql.SQLException

/**
 * 벤더링 패치: 원본의 WasmDBFactory(ZeroFs 공유 FS 관리) 대체.
 * 런타임 공유/refcount 는 modules/sqlite 의 WorkerDbRuntimes 가 담당하므로 여기는 순수 팩토리.
 */
class WorkerDBFactory {
    @Throws(SQLException::class)
    fun create(
        url: String, fileName: String, config: SQLiteConfig, isMemory: Boolean
    ): WorkerDB {
        return WorkerDB(url, fileName, config, isMemory)
    }

    @Throws(SQLException::class)
    fun close(db: WorkerDB) {
        db.close()
    }
}
