package org.example.sqlite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * SQLITE_DEFAULT_MEMSTATUS=0 효과 측정용 마이크로 벤치 (후보 ① 성능 트랙, BUILD.md).
 *
 * 두 케이스로 mem0 static mutex(전 인스턴스 공유) 경합/오버헤드를 노린다:
 *  A) fine-grained INSERT 루프 = 단일 워커, malloc 마다 uncontended mem0 mutex enter/leave 오버헤드
 *     (SerializeTest 핫케이스의 절단면).
 *  B) 동시 GROUP BY/ORDER BY 읽기 = 다중 pthread 가 sorter/ephemeral b-tree malloc 으로 mem0 경합.
 *
 * 타이밍만 stdout 으로 찍고 정확성만 assert (시간 자체는 게이트 아님 — 변경 전/후 비교용).
 * 안정화를 위해 워밍업 1회 + 본측정 3회 best 를 출력.
 *
 * 평소엔 @Disabled (일반 스위트에 ~60s 부하 안 줌) — 성능 트랙 재측정 시
 * `./gradlew :modules:sqlite:test --tests MemstatusBenchTest --info` 로 두 줄을 끄고 돌린다.
 * 측정 이력 (best-of-3, JEP491 JVM): MEMSTATUS=1 → A 371ms / B 15861ms,
 *                                    MEMSTATUS=0 → A 348ms(-6.2%) / B 15243ms(-3.9%).
 */
@Disabled("성능 벤치 — 수동 실행 (BUILD.md 후보 ① 성능 트랙)")
class MemstatusBenchTest {

    private fun bestOf(times: Int, warmup: () -> Unit, body: () -> Unit): Long {
        warmup()
        var best = Long.MAX_VALUE
        repeat(times) {
            val t0 = System.nanoTime()
            body()
            val dt = System.nanoTime() - t0
            if (dt < best) best = dt
        }
        return best
    }

    @Test
    fun `bench A - fine-grained INSERT 루프 (단일 워커, uncontended mem0)`() {
        val dir = Files.createTempDirectory("memstat-a")
        SqliteWal.open(dir, "a.db", readers = 1).use { db ->
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, a INTEGER, b TEXT)")
            val n = 50_000
            val run: () -> Unit = {
                db.exec("DELETE FROM t")
                db.exec("BEGIN")
                repeat(n) { i -> db.exec("INSERT INTO t(a,b) VALUES (?, ?)", i, "row-$i") }
                db.exec("COMMIT")
            }
            val best = bestOf(3, warmup = run, body = run)
            assertEquals(n.toLong(), db.queryLong("SELECT count(*) FROM t"))
            println("[BENCH A] fine-grained ${n} INSERT: best=${best / 1_000_000}ms (${"%.0f".format(n / (best / 1e9))} ops/s)")
        }
    }

    @Test
    fun `bench B - 동시 GROUP BY-ORDER BY 읽기 (다중 pthread mem0 경합)`() {
        val dir = Files.createTempDirectory("memstat-b")
        SqliteWal.open(dir, "b.db", readers = 8).use { db ->
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, tag INTEGER, v TEXT)")
            db.exec("BEGIN")
            val rows = 5_000
            repeat(rows) { i -> db.exec("INSERT INTO t(tag,v) VALUES (?, ?)", i % 50, "v-${i % 200}") }
            db.exec("COMMIT")

            val readers = 8; val each = 300
            // GROUP BY + ORDER BY → 매 쿼리가 sorter/ephemeral b-tree 를 malloc/free (mem0 경합 유발)
            val sql = "SELECT tag, count(*) c FROM t GROUP BY tag ORDER BY c DESC, tag"
            val run: () -> Unit = {
                Executors.newVirtualThreadPerTaskExecutor().use { ex ->
                    val fs = ArrayList<Future<*>>()
                    repeat(readers) {
                        fs += ex.submit { repeat(each) { db.query(sql) } }
                    }
                    fs.forEach { it.get() }
                }
            }
            val best = bestOf(3, warmup = run, body = run)
            val total = readers * each
            assertEquals(50L, db.queryLong("SELECT count(DISTINCT tag) FROM t"))
            println("[BENCH B] ${readers}r×${each} GROUP/ORDER: best=${best / 1_000_000}ms (${"%.0f".format(total / (best / 1e9))} q/s)")
        }
    }
}
