package org.example.sqlite

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M3 Stage 3: 같은 파일을 여러 워커가 WAL 로 **동시 접근**. 라이브러리 [JvmVfsRuntime] 사용.
 * writer 2(각 50) + reader 1(100) 동시 → 최종 100행, integrity ok.
 */
class JvmVfsStage3Test {

    @Test
    fun `다중 워커 동시 WAL - 같은 파일 (라이브러리 API)`() {
        val hostDir = Files.createTempDirectory("jvmvfs3")
        JvmVfsRuntime.open(preopens = mapOf("/db" to hostDir)).use { rt ->
            // 메인: 테이블 생성 후 닫기 (체크포인트되어 워커가 봄)
            val setup = rt.openDb("/db/test.db")
            rt.exec(setup, "PRAGMA journal_mode=WAL")
            assertEquals(0, rt.exec(setup, "CREATE TABLE t(id INTEGER PRIMARY KEY, tag INTEGER)"))
            rt.closeDb(setup)

            // writer 2 + reader 1 동시
            val WRITERS = 2; val PER = 50; val READS = 100
            val benches = buildList {
                repeat(WRITERS) { add(rt.spawnBench("/db/test.db", mode = 0, count = PER)) }
                add(rt.spawnBench("/db/test.db", mode = 1, count = READS))
            }
            rt.awaitAll(benches)

            benches.forEachIndexed { i, b -> assertEquals(0, b.rc, "워커 $i (mode=${b.mode}) rc=${b.rc}") }
            assertTrue(rt.workerErrors.isEmpty(), "워커 스레드 오류: ${rt.workerErrors.toList()}")

            // 검증
            val check = rt.openDb("/db/test.db")
            val total = rt.scalarLong(check, "SELECT count(*) FROM t")
            val integ = rt.scalarText(check, "PRAGMA integrity_check")
            rt.closeDb(check)
            println("[JVMVFS3] 최종 행 수=$total (기대=${WRITERS * PER}), integrity=$integ")
            assertEquals((WRITERS * PER).toLong(), total, "writer 삽입 보존")
            assertEquals("ok", integ)
        }
    }
}
