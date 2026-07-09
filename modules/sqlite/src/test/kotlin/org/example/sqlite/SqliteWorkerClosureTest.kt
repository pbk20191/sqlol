package org.example.sqlite

import org.example.sqlite.core.InternalRuntimeApi
import org.example.sqlite.core.JvmVfsRuntime
import org.example.sqlite.core.SqliteDataSource
import org.example.sqlite.core.SqliteWorker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * [org.example.sqlite.core.SqliteWorker] 직접 실행 모델 (§9.2) — caller 스레드가 [org.example.sqlite.core.SqliteWorker.run] 으로 임의 다단계
 * 클로저를 per-child 락 직렬화 하에 직접 실행한다 (큐/future 마샬링 없음).
 *
 * 핵심 검증: (1) 한 클로저 안의 다단계 트랜잭션 (락 보유 = 인터리빙 없음), (2) raw export 조립으로
 * 다중 행 수집 + 타입드 반환 (ResultSet 전구체), (3) 클로저 예외가 caller 에 그대로 전파 + 워커 생존.
 */
class SqliteWorkerClosureTest {

    @OptIn(InternalRuntimeApi::class)
    private fun <T> withWorker(block: (JvmVfsRuntime, SqliteWorker) -> T): T {
        val dir = Files.createTempDirectory("sqlitewk")
        val h = SqliteDataSource.openRuntime(dir, "closure.db") as SqliteDataSource.RuntimeHandle
        try {
            val w = SqliteWorker.spawn(h.rt, h.guestPath)
            try {
                val r = block(h.rt, w)
                // 위조 spawn(start_func==0) 판별 회귀 — spawnPthread 만 쓰는 경로에선 위임 0 이어야 한다.
                // (guest 발 sorter 위임 ≥1 방향은 SqliteWalTest 가 담당)
                assertEquals(0, h.rt.delegatedSpawns.get())
                return r
            } finally {
                w.stop()
                h.rt.close()
            }
        } finally {
            h.ownerLock!!.close()
        }
    }

    @Test
    fun `다단계 클로저 - 트랜잭션이 한 호출 안에서 원자적`() = withWorker { _, w ->
        w.run { s ->
            check(s.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)") == 0)
        }
        // 커밋되는 트랜잭션 — BEGIN~COMMIT 이 클로저 1개 = 락 1회 보유 (사이에 다른 호출 불가)
        w.run { s ->
            check(s.exec("BEGIN") == 0)
            check(s.run("INSERT INTO t(name) VALUES ('a')", isQuery = false).rc == 0)
            check(s.run("INSERT INTO t(name) VALUES ('b')", isQuery = false).rc == 0)
            check(s.exec("COMMIT") == 0)
        }
        // 롤백되는 트랜잭션 — 중간 상태(열린 트랜잭션)가 클로저 밖으로 새지 않음
        w.run { s ->
            check(s.exec("BEGIN") == 0)
            check(s.run("INSERT INTO t(name) VALUES ('zombie')", isQuery = false).rc == 0)
            check(s.exec("ROLLBACK") == 0)
        }
        assertEquals(2L, w.run("SELECT count(*) FROM t", isQuery = true).long)
    }

    @Test
    fun `raw export 클로저 - 다중 행 수집과 타입드 반환`() = withWorker { _, w ->
        w.run { s ->
            check(s.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)") == 0)
            for (n in listOf("alice", "bob", "carol")) {
                check(s.run("INSERT INTO t(name) VALUES ('$n')", isQuery = false).rc == 0)
            }
        }
        // prepare→step 루프→finalize 를 클로저가 직접 조립 — List<String> 그대로 반환 (제네릭 T)
        val names: List<String> = w.run { s ->
            val pp = s.x.malloc(4)
            try {
                val prc = s.x.sqlite3PrepareV2(s.db, s.sqlPtr("SELECT name FROM t ORDER BY id"), -1, pp, 0)
                check(prc == 0) { "prepare rc=$prc (${s.errmsg(prc)})" }
                val st = s.mem.readInt(pp)
                val out = mutableListOf<String>()
                while (s.x.sqlite3Step(st) == 100) {
                    out += s.mem.readCString(s.x.sqlite3ColumnText(st, 0))
                }
                s.x.sqlite3Finalize(st)
                out
            } finally {
                s.x.free(pp)
            }
        }
        assertEquals(listOf("alice", "bob", "carol"), names)
    }

    @Test
    fun `클로저 예외 - caller 에 그대로 전파되고 워커는 생존`() = withWorker { _, w ->
        val thrown = runCatching {
            w.run<Unit> { error("클로저 실패") }
        }.exceptionOrNull()
        // 직접 실행이라 래퍼(ExecutionException) 없이 원본 예외 그대로
        assertTrue(thrown is IllegalStateException && thrown.message == "클로저 실패") { "got: $thrown" }
        assertEquals(1L, w.run("SELECT 1", isQuery = true).long)
    }
}
