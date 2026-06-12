package org.example.sqlite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * rc=26(NOTADB) 회귀 감시 — 함정 3(벌크 position 레이스)의 역사적 발화점이었던 IO-헤비 워크로드를
 * 반복 실행한다. 근원은 [StatelessBulkMemory] 로 해소됨 (BUILD.md §9.1) — 이 테스트는 그 사실이
 * 계속 참인지 지키는 보초. 실패 시 즉시 throw (스택 보존) — 그때는 §9.1 의 이분법
 * (wal_autocheckpoint=0 / 행수 sweep)으로 추적할 것.
 */
class Rc26ReproTest {

    private fun once(rows: Int) {
        val dir = Files.createTempDirectory("rc26")
        SqliteWal.open(dir, "r.db", readers = 4).use { db ->
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)")
            db.exec(
                "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<$rows) " +
                "INSERT INTO t(id,v) SELECT x, hex(randomblob(48)) FROM c"
            )
            assertEquals(rows.toLong(), db.queryLong("SELECT count(*) FROM t"))
            assertEquals("ok", db.queryText("PRAGMA integrity_check"))
        }
    }

    @Test
    fun `회귀 감시 - 100k IO-헤비 반복`() {
        repeat(10) { once(100_000) }
    }
}
