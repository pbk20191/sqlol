package org.example.sqlite.jdbc.jdbc4

import org.example.sqlite.jdbc.SQLiteConnection
import org.example.sqlite.jdbc.core.CoreStatement
import org.example.sqlite.jdbc.jdbc3.JDBC3DatabaseMetaData
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.RowIdLifetime
import java.sql.SQLException

class JDBC4DatabaseMetaData(conn: SQLiteConnection) : JDBC3DatabaseMetaData(conn) {
    // JDBC 4
    @Throws(ClassCastException::class)
    override fun <T> unwrap(iface: Class<T?>): T? {
        return iface.cast(this)
    }

    override fun isWrapperFor(iface: Class<*>): Boolean {
        return iface.isInstance(this)
    }

    @Throws(SQLException::class)
    override fun getRowIdLifetime(): RowIdLifetime {
        // getRowId/RowId 접근자를 지원하지 않으므로 UNSUPPORTED 가 정합적 (rowid 자체는 존재)
        return RowIdLifetime.ROWID_UNSUPPORTED
    }

    @Throws(SQLException::class)
    override fun getSchemas(catalog: String?, schemaPattern: String?): ResultSet? {
        // SQLite 는 스키마 개념이 없다 — 무-인자 getSchemas 와 동일한 빈 결과
        return getSchemas()
    }

    @Throws(SQLException::class)
    override fun supportsStoredFunctionsUsingCallSyntax(): Boolean {
        return false   // 저장 함수/프로시저 없음
    }

    @Throws(SQLException::class)
    override fun autoCommitFailureClosesAllResultSets(): Boolean {
        return false
    }

    @Throws(SQLException::class)
    override fun getClientInfoProperties(): ResultSet {
        return (db.createStatement() as CoreStatement).executeQuery(
            "select '' as NAME, 0 as MAX_LEN, '' as DEFAULT_VALUE, '' as DESCRIPTION limit 0",
            true
        )!!
    }

    /** `pragma_function_list` 기반 — 활성화된 확장(FTS5/JSON/math …)의 함수까지 전부 열거된다. */
    @Throws(SQLException::class)
    override fun getFunctions(
        catalog: String?,
        schemaPattern: String?,
        functionNamePattern: String?
    ): ResultSet {
        // JDBC 규약: null = 필터 없음("%"), 빈 문자열은 빈 이름만 매칭(사실상 0 행) — 둘을 구분해야 한다.
        val pattern = if (functionNamePattern == null) "%" else escape(functionNamePattern)
        return (db.createStatement() as CoreStatement).executeQuery(
            """
            select null as FUNCTION_CAT, null as FUNCTION_SCHEM, name as FUNCTION_NAME,
                   null as REMARKS, ${DatabaseMetaData.functionNoTable} as FUNCTION_TYPE,
                   name as SPECIFIC_NAME
            from pragma_function_list where name like '$pattern' order by name
            """.trimIndent(),
            true
        )!!
    }

    @Throws(SQLException::class)
    override fun getFunctionColumns(
        a: String?,
        b: String?,
        c: String?,
        d: String?
    ): ResultSet {
        // 함수 인자 메타데이터는 SQLite 가 노출하지 않는다 — JDBC 규격 컬럼의 빈 결과
        return (db.createStatement() as CoreStatement).executeQuery(
            """
            select null as FUNCTION_CAT, null as FUNCTION_SCHEM, '' as FUNCTION_NAME,
                   '' as COLUMN_NAME, 0 as COLUMN_TYPE, 0 as DATA_TYPE, '' as TYPE_NAME,
                   0 as PRECISION, 0 as LENGTH, 0 as SCALE, 0 as RADIX, 0 as NULLABLE,
                   null as REMARKS, 0 as CHAR_OCTET_LENGTH, 0 as ORDINAL_POSITION,
                   '' as IS_NULLABLE, '' as SPECIFIC_NAME limit 0
            """.trimIndent(),
            true
        )!!
    }

    @Throws(SQLException::class)
    override fun getPseudoColumns(
        catalog: String?,
        schemaPattern: String?,
        tableNamePattern: String?,
        columnNamePattern: String?
    ): ResultSet {
        // rowid 는 SELECT 가능하지만 컬럼 메타로는 노출하지 않는다 (WITHOUT ROWID 구분 불가) — 빈 결과
        return (db.createStatement() as CoreStatement).executeQuery(
            """
            select null as TABLE_CAT, null as TABLE_SCHEM, '' as TABLE_NAME, '' as COLUMN_NAME,
                   0 as DATA_TYPE, 0 as COLUMN_SIZE, 0 as DECIMAL_DIGITS, 0 as NUM_PREC_RADIX,
                   '' as COLUMN_USAGE, null as REMARKS, 0 as CHAR_OCTET_LENGTH, '' as IS_NULLABLE
                   limit 0
            """.trimIndent(),
            true
        )!!
    }

    @Throws(SQLException::class)
    override fun generatedKeyAlwaysReturned(): Boolean {
        return true   // last_insert_rowid 기반 — getGeneratedKeys 는 항상 조회 가능
    }
}
