package org.example.sqlite.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * java.sql 전 구간 스모크 — DriverManager → 벤더링 sqlite4j(JDBC/Statement/ResultSet) → WorkerDB →
 * WorkerDbPort → wasm pthread 워커. 핵심 검증: (1) 파일 DB CRUD + PreparedStatement + 다중 행 RS,
 * (2) :memory: (커넥션별 사유), (3) 같은 파일 다중 커넥션 (런타임 공유 + WAL), (4) 트랜잭션
 * commit/rollback, (5) 한 커넥션 위 커서 2개 인터리빙 (raw 절단면이라 가능해야 함).
 */
class JdbcSmokeTest {

    @Test
    void fileDb_crud_preparedStatement_resultSet() throws Exception {
        Path dir = Files.createTempDirectory("jdbcsmoke");
        String url = "jdbc:sqlite:" + dir.resolve("app.db");
        try (Connection con = DriverManager.getConnection(url)) {
            try (Statement st = con.createStatement()) {
                st.executeUpdate("CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT, score REAL)");
                assertEquals(1, st.executeUpdate("INSERT INTO t(name, score) VALUES ('alice', 1.5)"));
            }
            try (PreparedStatement ps =
                    con.prepareStatement("INSERT INTO t(name, score) VALUES (?, ?)")) {
                ps.setString(1, "böb — 한글");
                ps.setDouble(2, 2.5);
                assertEquals(1, ps.executeUpdate());
                ps.setString(1, "carol");
                ps.setNull(2, java.sql.Types.REAL);
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps =
                            con.prepareStatement("SELECT id, name, score FROM t ORDER BY id");
                    ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getLong("id"));
                assertEquals("alice", rs.getString("name"));
                assertTrue(rs.next());
                assertEquals("böb — 한글", rs.getString(2));
                assertEquals(2.5, rs.getDouble(3));
                assertTrue(rs.next());
                rs.getDouble("score");
                assertTrue(rs.wasNull());
                assertFalse(rs.next());
            }
            assertTrue(con.getMetaData().getDatabaseProductVersion().startsWith("3."));
        }
    }

    @Test
    void memoryDb_isPerConnection() throws Exception {
        try (Connection a = DriverManager.getConnection("jdbc:sqlite::memory:");
                Connection b = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = a.createStatement()) {
                st.executeUpdate("CREATE TABLE t(v INTEGER)");
                st.executeUpdate("INSERT INTO t VALUES (7)");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM t")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
            }
            // b 는 a 와 분리된 사유 DB (xerial 시맨틱)
            try (Statement st = b.createStatement()) {
                st.executeUpdate("CREATE TABLE t(v INTEGER)"); // 충돌 없이 생성 가능 = 분리 증명
            }
        }
    }

    @Test
    void sameFile_twoConnections_shareRuntime() throws Exception {
        Path dir = Files.createTempDirectory("jdbcsmoke");
        String url = "jdbc:sqlite:" + dir.resolve("shared.db");

        try (Connection w = DriverManager.getConnection(url);
                Connection r = DriverManager.getConnection(url)) {
            try (Statement st = w.createStatement()) {
                st.executeUpdate("CREATE TABLE t(v INTEGER)");
                st.executeUpdate("INSERT INTO t VALUES (1), (2), (3)");
            }
            // 다른 커넥션(=다른 워커, 같은 런타임/WAL)에서 즉시 보임
            try (Statement st = r.createStatement();
                    ResultSet rs = st.executeQuery("SELECT count(*) FROM t")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
        }
        // 전부 닫힌 뒤 재오픈 (런타임 refcount 0 → 정리 → 재생성 경로)
        try (Connection c = DriverManager.getConnection(url);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            assertTrue(rs.next());
            assertEquals("ok", rs.getString(1));
        }
    }

    @Test
    void transaction_commitAndRollback() throws Exception {
        Path dir = Files.createTempDirectory("jdbcsmoke");
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("tx.db"))) {
            try (Statement st = con.createStatement()) {
                st.executeUpdate("CREATE TABLE t(v INTEGER)");
            }
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (1)");
            }
            con.rollback();
            try (Statement st = con.createStatement()) {
                st.executeUpdate("INSERT INTO t VALUES (2)");
            }
            con.commit();
            con.setAutoCommit(true);
            try (Statement st = con.createStatement();
                    ResultSet rs = st.executeQuery("SELECT v FROM t")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void twoOpenCursors_interleaved_onOneConnection() throws Exception {
        Path dir = Files.createTempDirectory("jdbcsmoke");
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("cur.db"))) {
            try (Statement st = con.createStatement()) {
                st.executeUpdate("CREATE TABLE t(v INTEGER)");
                st.executeUpdate("INSERT INTO t VALUES (1), (2), (3), (4)");
            }
            try (PreparedStatement p1 = con.prepareStatement("SELECT v FROM t ORDER BY v");
                    PreparedStatement p2 = con.prepareStatement("SELECT v FROM t ORDER BY v DESC");
                    ResultSet r1 = p1.executeQuery();
                    ResultSet r2 = p2.executeQuery()) {
                // 두 커서를 교차로 step — raw 절단면(연산 단위 마샬링)이라 가능해야 한다
                assertTrue(r1.next());
                assertTrue(r2.next());
                assertEquals(1, r1.getInt(1));
                assertEquals(4, r2.getInt(1));
                assertTrue(r1.next());
                assertTrue(r2.next());
                assertEquals(2, r1.getInt(1));
                assertEquals(3, r2.getInt(1));
            }
        }
    }
}
