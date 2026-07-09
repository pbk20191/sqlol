package org.example.sqlite.jdbc

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.sql.DriverManager
import java.sql.RowIdLifetime
import java.sql.SQLException
import java.sql.Types
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JDBC4 표면 추가 구현 기능 테스트:
 *  ① carray 기반 Array 바인딩 (createArrayOf/setArray)
 *  ② Blob/Clob/NClob 팩토리 + 쓰기 가능 구현
 *  ③ PRAGMA 기반 DatabaseMetaData (getFunctions 등)
 */
class Jdbc4FeatureTest {

    private fun memConn() = DriverManager.getConnection("jdbc:sqlite::memory:") as SQLiteConnection

    // ==== ① Array (carray) ====

    @Test
    fun `createArrayOf + setArray - BIGINT IN 리스트 바인딩`() {
        memConn().use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT)") }
            conn.createStatement().use { it.execute("INSERT INTO t VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d')") }
            val arr = conn.createArrayOf("BIGINT", arrayOf(1L, 3L, 4L))!!
            conn.prepareStatement("SELECT count(*) FROM t WHERE id IN (SELECT value FROM carray(?))").use { ps ->
                ps.setArray(1, arr)
                val rs = ps.executeQuery()
                assertTrue(rs.next())
                assertEquals(3, rs.getInt(1))
            }
        }
    }

    @Test
    fun `setArray - TEXT 배열 (null 원소 포함)`() {
        memConn().use { conn ->
            val arr = conn.createArrayOf("TEXT", arrayOf("b", null, "a"))!!
            conn.prepareStatement("SELECT value FROM carray(?) ORDER BY value").use { ps ->
                ps.setArray(1, arr)
                val rs = ps.executeQuery()
                assertTrue(rs.next()); assertNull(rs.getString(1))   // NULL 이 정렬 최솟값
                assertTrue(rs.next()); assertEquals("a", rs.getString(1))
                assertTrue(rs.next()); assertEquals("b", rs.getString(1))
                assertFalse(rs.next())
            }
        }
    }

    @Test
    fun `setArray - DOUBLE 배열 + 재실행(재바인딩) 안전`() {
        memConn().use { conn ->
            conn.prepareStatement("SELECT sum(value) FROM carray(?)").use { ps ->
                ps.setArray(1, conn.createArrayOf("DOUBLE", arrayOf(1.5, 2.5)))
                ps.executeQuery().use { rs -> rs.next(); assertEquals(4.0, rs.getDouble(1)) }
                // 같은 stmt 로 다른 배열 재바인딩 — TRANSIENT 딥카피라 이전 버퍼와 무관해야 함
                ps.setArray(1, conn.createArrayOf("DOUBLE", arrayOf(10.0, 20.0, 30.0)))
                ps.executeQuery().use { rs -> rs.next(); assertEquals(60.0, rs.getDouble(1)) }
            }
        }
    }

    @Test
    fun `createArrayOf - Array 인터페이스 계약 + 미지원 타입 거부`() {
        memConn().use { conn ->
            val arr = conn.createArrayOf("INTEGER", arrayOf(7L, 8L))!!
            assertEquals(Types.BIGINT, arr.baseType)   // sqlite INTEGER = 64-bit
            @Suppress("UNCHECKED_CAST")
            assertContentEquals(arrayOf<Any?>(7L, 8L), arr.array as Array<Any?>)
            arr.free()
            assertFailsWith<SQLException> { arr.array }               // free 후 접근 금지
            assertFailsWith<SQLException> { conn.createArrayOf("STRUCT", arrayOf()) }
        }
    }

    // ==== ② Blob / Clob / NClob ====

    @Test
    fun `createBlob - setBlob - getBlob 라운드트립`() {
        memConn().use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE b(v BLOB)") }
            val blob = conn.createBlob()!!
            blob.setBytes(1, byteArrayOf(10, 20, 30, 40))
            assertEquals(4L, blob.length())
            conn.prepareStatement("INSERT INTO b VALUES (?)").use { ps ->
                ps.setBlob(1, blob)
                ps.executeUpdate()
            }
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT v FROM b")
                assertTrue(rs.next())
                val out = rs.getBlob(1)!!
                assertContentEquals(byteArrayOf(10, 20, 30, 40), out.getBytes(1, 4))
            }
        }
    }

    @Test
    fun `Blob - setBinaryStream과 truncate, position`() {
        memConn().use { conn ->
            val blob = conn.createBlob()!!
            blob.setBinaryStream(1).use { it.write(byteArrayOf(1, 2, 3, 4, 5)) }
            assertEquals(5L, blob.length())
            assertEquals(3L, blob.position(byteArrayOf(3, 4), 1))
            blob.truncate(2)
            assertContentEquals(byteArrayOf(1, 2), blob.getBytes(1, 10))
        }
    }

    @Test
    fun `setBlob(stream+length) - 조용한 no-op 이 아니어야 함`() {
        // JDBC4PreparedStatement.setBinaryStream(pi, x, length: Long) 본문이 비어 있던 버그 회귀 가드
        memConn().use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE b(v BLOB)") }
            conn.prepareStatement("INSERT INTO b VALUES (?)").use { ps ->
                ps.setBlob(1, ByteArrayInputStream(byteArrayOf(9, 8, 7)), 3L)
                ps.executeUpdate()
            }
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT v FROM b")
                assertTrue(rs.next())
                assertContentEquals(byteArrayOf(9, 8, 7), rs.getBytes(1))
            }
        }
    }

    @Test
    fun `createClob과 createNClob - setClob 라운드트립 + 쓰기 가능 Clob`() {
        memConn().use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE c(v TEXT)") }
            val clob = conn.createClob()!!
            clob.setString(1, "hello")
            clob.setString(6, " world")            // 이어붙이기
            assertEquals(11L, clob.length())
            clob.truncate(5)
            conn.prepareStatement("INSERT INTO c VALUES (?)").use { ps ->
                ps.setClob(1, clob)
                ps.executeUpdate()
            }
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT v FROM c")
                assertTrue(rs.next())
                val out = rs.getClob(1)!!
                assertEquals("hello", out.getSubString(1, 5))
                assertEquals(4L, out.position("lo", 1))
            }
            // NClob 팩토리도 동작
            val nclob = conn.createNClob()!!
            nclob.setString(1, "N")
            assertEquals("N", nclob.getSubString(1, 1))
        }
    }

    @Test
    fun `ResultSet getNClob - 스텁 해제`() {
        memConn().use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE c(v TEXT)") }
            conn.createStatement().use { it.execute("INSERT INTO c VALUES ('ntext')") }
            conn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT v FROM c")
                assertTrue(rs.next())
                assertEquals("ntext", rs.getNClob(1)!!.getSubString(1, 5))
            }
        }
    }

    // ==== ③ PRAGMA 기반 DatabaseMetaData ====

    @Test
    fun `getFunctions - pragma function_list 기반 목록`() {
        memConn().use { conn ->
            val md = conn.metaData
            val rs = md.getFunctions(null, null, "json_%")
            val names = buildList { while (rs.next()) add(rs.getString("FUNCTION_NAME")) }
            assertTrue(names.contains("json_extract"), "json_extract 없음: $names")
            // JDBC 패턴의 '_' 는 1문자 와일드카드 — jsonb_* 도 정당하게 매치된다
            assertTrue(names.all { it.startsWith("json") })
            assertTrue(names == names.sorted(), "이름순 정렬이어야 함")
            // 전체 목록엔 확장 함수(FTS5 등 활성 확인용 abs 포함)도 보인다
            val all = md.getFunctions(null, null, null)
            val allNames = buildList { while (all.next()) add(all.getString(3)) }
            assertTrue(allNames.contains("abs"))
        }
    }

    @Test
    fun `getFunctionColumns과 getClientInfoProperties - 빈 RS (throw 아님)`() {
        memConn().use { conn ->
            val md = conn.metaData
            md.getFunctionColumns(null, null, "%", "%").use { rs ->
                assertFalse(rs.next())
                assertEquals("FUNCTION_NAME", rs.metaData.getColumnName(3))
            }
            md.clientInfoProperties.use { rs ->
                assertFalse(rs.next())
                assertEquals("NAME", rs.metaData.getColumnName(1))
            }
        }
    }

    @Test
    fun `나머지 JDBC4 메타데이터 스텁 해제`() {
        memConn().use { conn ->
            val md = conn.metaData
            assertEquals(RowIdLifetime.ROWID_UNSUPPORTED, md.rowIdLifetime)
            assertFalse(md.supportsStoredFunctionsUsingCallSyntax())
            assertFalse(md.autoCommitFailureClosesAllResultSets())
            assertTrue(md.generatedKeyAlwaysReturned())
            md.getSchemas(null, "%").use { rs -> assertFalse(rs.next()) }   // 무-스키마 (본 getSchemas 와 동일)
        }
    }
}
