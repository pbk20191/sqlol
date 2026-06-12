package org.example.sqlite

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteTest {

    @Test
    fun `라이브러리 버전`() {
        SqliteDb.openMemory().use { db ->
            assertEquals("3.53.1", db.libVersion)
        }
    }

    @Test
    fun `기본 CRUD 와 파라미터 바인딩`() {
        SqliteDb.openMemory().use { db ->
            db.exec("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)")
            assertEquals(1, db.update("INSERT INTO t(name) VALUES (?)", "alice"))
            db.update("INSERT INTO t(name) VALUES (?)", "bob")
            assertEquals(2L, db.lastInsertRowId())

            val names = db.query("SELECT name FROM t ORDER BY id") { it.getText(0) }
            assertEquals(listOf("alice", "bob"), names)
        }
    }

    @Test
    fun `전체 타입 바인딩과 컬럼 타입`() {
        SqliteDb.openMemory().use { db ->
            db.exec("CREATE TABLE v(i INTEGER, l INTEGER, d REAL, s TEXT, b BLOB, z INTEGER)")
            val blob = byteArrayOf(1, 2, 3, 0, 127, -1)
            db.update(
                "INSERT INTO v VALUES (?,?,?,?,?,?)",
                42, 9_000_000_000L, 3.14159, "héllo", blob, null,
            )

            val rows = db.query("SELECT i,l,d,s,b,z FROM v") { st ->
                val values = listOf(st.getInt(0), st.getLong(1), st.getDouble(2), st.getText(3), st.getBlob(4), st.getValue(5))
                val types = (0 until st.columnCount).map { st.columnType(it) }
                values to types
            }
            val (values, types) = rows.single()

            assertEquals(42, values[0])
            assertEquals(9_000_000_000L, values[1])
            assertEquals(3.14159, values[2] as Double, 1e-9)
            assertEquals("héllo", values[3])
            assertContentEquals(blob, values[4] as ByteArray)
            assertNull(values[5])

            assertEquals(
                listOf(
                    SqliteType.INTEGER, SqliteType.INTEGER, SqliteType.FLOAT,
                    SqliteType.TEXT, SqliteType.BLOB, SqliteType.NULL,
                ),
                types,
            )
        }
    }

    @Test
    fun `getValue 가 행 전체를 타입에 맞게 매핑`() {
        SqliteDb.openMemory().use { db ->
            db.exec("CREATE TABLE m(a,b,c)")
            db.update("INSERT INTO m VALUES (?,?,?)", 1L, "x", null)
            val row = db.query("SELECT a,b,c FROM m") { it.row() }.single()
            assertEquals(listOf<Any?>(1L, "x", null), row)
        }
    }

    @Test
    fun `컴파일된 확장 기능 - JSON, math, FTS5, RTree, Geopoly`() {
        SqliteDb.openMemory().use { db ->
            assertEquals(42L, db.query("SELECT json_extract('{\"a\":42}','$.a')") { it.getLong(0) }.single())
            assertEquals(1024.0, db.query("SELECT pow(2,10)") { it.getDouble(0) }.single(), 1e-9)

            db.exec("CREATE VIRTUAL TABLE ft USING fts5(body)")
            db.update("INSERT INTO ft(body) VALUES (?)", "the quick brown fox")
            assertEquals(
                listOf("the quick brown fox"),
                db.query("SELECT body FROM ft WHERE ft MATCH ?", "quick") { it.getText(0) },
            )

            db.exec("CREATE VIRTUAL TABLE ri USING rtree(id, minX, maxX)")
            db.exec("CREATE VIRTUAL TABLE geo USING geopoly(label)")
        }
    }

    @Test
    fun `파일 DB 에서 WAL 모드 (단일 커넥션, EXCLUSIVE)`() {
        val dir = Files.createTempDirectory("sqlite-wal-test")
        SqliteDb.openFile(dir, "test.db").use { db ->
            db.exec("PRAGMA locking_mode=EXCLUSIVE")               // 첫 접근 전 필수
            val mode = db.query("PRAGMA journal_mode=WAL") { it.getText(0) }.single()
            assertEquals("wal", mode)                              // shm 없이 힙 wal-index 사용

            db.exec("CREATE TABLE log(msg TEXT)")
            db.update("INSERT INTO log VALUES (?)", "hello wal")
            assertEquals(1L, db.query("SELECT count(*) FROM log") { it.getLong(0) }.single())
        }
        assertTrue(Files.exists(dir.resolve("test.db")), "DB 파일이 디스크에 생성되어야 함")
    }
}
