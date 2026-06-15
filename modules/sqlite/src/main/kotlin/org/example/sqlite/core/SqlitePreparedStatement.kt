package org.example.sqlite.core

/**
 * ?-바인딩 prepared statement — [SqliteConnection.prepare] 로 생성 (JDBC PreparedStatement 대응).
 *
 * stmt 는 그 커넥션의 워커가 소유한다 (stmt LRU 캐시 밖, 사용자 수명) — 모든 실행은 워커 큐로
 * 직렬화되고, 파라미터는 [set]/[setAll] 로 JVM 쪽에 모아 뒀다가 execute 시 **한 태스크**로
 * 바인드+실행한다 (행당 왕복 1회, 값 복사는 TRANSIENT — 게스트 버퍼는 워커의 스크래치 재사용).
 *
 * JDBC 와 같이 **객체 자체는 스레드 안전이 아니다** (set 과 execute 의 인터리빙은 호출자 책임).
 * 미설정 파라미터는 NULL 로 바인딩된다 (sqlite 시맨틱).
 *
 * close 규율: [close] 멱등. 커넥션이 먼저 닫히면 stmt 는 워커 종료가 자동 finalize — 이후 close 는 no-op.
 *
 * ([SqliteWal] 에는 이 표면이 없다 — stmt 가 특정 워커 소유라 공유 reader 큐의 워크스틸링과 호환되지
 * 않는다. 풀형에서 바인딩은 vararg 표면(워커별 LRU 캐시 적중)을 쓸 것.)
 */
class SqlitePreparedStatement internal constructor(
    private val con: SqliteConnection,
    private val stmt: Int,
    val parameterCount: Int,
    private val sql: String,
) : AutoCloseable {

    private val params = arrayOfNulls<Any?>(parameterCount)
    @Volatile private var closed = false

    /** [index] 는 1-based (sqlite ?-순번). 지원 타입: Long/Int/Boolean/Double/Float/String/ByteArray/null. */
    fun set(index: Int, value: Any?): SqlitePreparedStatement {
        check(!closed) { "PreparedStatement closed: $sql" }
        require(index in 1..parameterCount) { "파라미터 인덱스 $index (유효: 1..$parameterCount)" }
        params[index - 1] = value
        return this
    }

    /** 전체 파라미터를 1번부터 채움 (개수 일치 강제). */
    fun setAll(vararg values: Any?): SqlitePreparedStatement {
        require(values.size == parameterCount) { "값 ${values.size}개 != ?-개수 $parameterCount" }
        values.forEachIndexed { i, v -> set(i + 1, v) }
        return this
    }

    fun clearParameters(): SqlitePreparedStatement {
        params.fill(null)
        return this
    }

    /** DML/DDL 실행 → 영향 행 수 (sqlite3_changes). rc!=0 이면 예외 (errmsg 포함). */
    fun execute(): Long {
        check(!closed) { "PreparedStatement closed: $sql" }
        val snapshot = params.toList()
        return con.submitTask(sql) { s ->
            val r = s.runUser(stmt, snapshot, isQuery = false)
            check(r.rc == 0) { "execute rc=${r.rc} (${r.text}): $sql" }
            s.x.sqlite3Changes(s.db).toLong()   // 같은 태스크 안 — 다른 문장이 끼어들 수 없음
        }
    }

    /** SELECT 첫 행 첫 컬럼을 정수로. */
    fun queryLong(): Long = scalar().long

    /** SELECT 첫 행 첫 컬럼을 텍스트로. 빈 결과 "". */
    fun queryText(): String = scalar().text

    /** 다중 행/열 SELECT — 전 행을 **한 태스크로** materialize (행당 왕복 없음). */
    fun query(): List<SqliteRow> {
        check(!closed) { "PreparedStatement closed: $sql" }
        val snapshot = params.toList()
        return con.submitTask(sql) { s -> s.runUserRows(stmt, snapshot) }
    }

    private fun scalar(): SqlResult {
        check(!closed) { "PreparedStatement closed: $sql" }
        val snapshot = params.toList()
        val r = con.submitTask(sql) { s -> s.runUser(stmt, snapshot, isQuery = true) }
        check(r.rc == 0) { "query rc=${r.rc} (${r.text}): $sql" }
        return r
    }

    /** stmt finalize (워커 태스크 제출). 멱등. 커넥션이 닫히는 중/닫힌 뒤면 no-op — 워커 종료가 정리했음. */
    override fun close() {
        if (closed) return
        closed = true
        con.onStatementClosed(this)
        runCatching { con.submitTask("finalize:$sql") { s -> s.finalizeUser(stmt) } }
    }

    /** 커넥션 close 경로에서 호출 — 제출 없이 닫힘 표시만 (finalize 는 Session.close 가 일괄). */
    internal fun markClosed() {
        closed = true
    }
}
