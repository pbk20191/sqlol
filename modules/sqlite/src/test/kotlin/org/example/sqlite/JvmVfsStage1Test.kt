package org.example.sqlite

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M3 Stage 1: 커스텀 JVM VFS 로 파일 DB 를 열고 WAL 이 실제로 진입하는지 (단일 커넥션).
 * 라이브러리 [JvmVfsRuntime] (AOT + 타입드 exports) 사용.
 * 성공 기준: PRAGMA journal_mode=WAL -> "wal" (이전엔 shm 부재로 "delete" 폴백이었음).
 */
class JvmVfsStage1Test {

    @Test
    fun `커스텀 VFS 로 단일 커넥션 WAL 진입`() {
        val dir = Files.createTempDirectory("jvmvfs1")
        JvmVfsRuntime.open(preopens = mapOf("/db" to dir)).use { rt ->
            val db = rt.openDb("/db/test.db")
            assertEquals("wal", rt.scalarText(db, "PRAGMA journal_mode=WAL"), "커스텀 VFS shm 으로 WAL 진입")
            assertEquals(0, rt.exec(db, "CREATE TABLE t(x)"))
            assertEquals(0, rt.exec(db, "INSERT INTO t VALUES (1),(2),(3)"))
            assertEquals(3L, rt.scalarLong(db, "SELECT count(*) FROM t"))
            rt.closeDb(db)
        }
        assertTrue(Files.exists(dir.resolve("test.db")), "db 파일 생성")
    }
}
