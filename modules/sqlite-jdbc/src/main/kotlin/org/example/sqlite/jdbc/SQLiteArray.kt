package org.example.sqlite.jdbc

import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.Types

/**
 * carray 기반 `java.sql.Array` — [java.sql.Connection.createArrayOf] 로 만들고
 * `IN (SELECT value FROM carray(?))` 형태의 SQL 에 [java.sql.PreparedStatement.setArray] 로 바인딩한다.
 *
 * 지원 기본 타입 3종 (carray 의 타입과 1:1):
 *  - 정수(INTEGER/BIGINT/SMALLINT/TINYINT) → INT64
 *  - 실수(REAL/DOUBLE/FLOAT/NUMERIC) → DOUBLE
 *  - 문자열(TEXT/VARCHAR/CHAR/NVARCHAR/CLOB) → TEXT (null 원소 = SQL NULL)
 *
 * 실제 바인딩은 [org.example.sqlite.jdbc.core.DB.sqlbind] 가 이 타입을 만나면
 * `bind_array` (carray_bind, TRANSIENT 딥카피) 로 디스패치한다.
 */
class SQLiteArray(typeName: String, elements: Array<Any?>) : java.sql.Array {

    internal enum class Kind { INT64, DOUBLE, TEXT }

    internal val kind: Kind
    private val baseTypeName: String = typeName.uppercase()
    private var elements: Array<Any?>? = elements.copyOf()

    init {
        kind = when (baseTypeName) {
            "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "INT" -> Kind.INT64
            "REAL", "DOUBLE", "FLOAT", "NUMERIC", "DECIMAL" -> Kind.DOUBLE
            "TEXT", "VARCHAR", "CHAR", "NVARCHAR", "NCHAR", "CLOB" -> Kind.TEXT
            else -> throw SQLException("createArrayOf: unsupported base type: $typeName")
        }
        // fail-fast: setArray/execute 시점이 아니라 createArrayOf 에서 거부해야 호출자가 처리할 수 있다.
        val exact = baseTypeName == "NUMERIC" || baseTypeName == "DECIMAL"
        elements.forEachIndexed { i, e ->
            when (kind) {
                Kind.INT64, Kind.DOUBLE -> {
                    if (e !is Number) throw SQLException(
                        "createArrayOf($baseTypeName): element[$i] is not a number: $e" +
                            " (carray 숫자 배열엔 NULL 슬롯이 없다 — NULL 이 필요하면 TEXT 배열 사용)",
                    )
                    if (exact && !exactAsDouble(e)) throw SQLException(
                        "createArrayOf($baseTypeName): element[$i]=$e loses precision as double" +
                            " — carray 는 int64/double 만 지원",
                    )
                }
                Kind.TEXT -> if (e?.toString()?.contains('\u0000') == true) throw SQLException(
                    "createArrayOf($baseTypeName): element[$i] contains embedded NUL — carray char* ABI 로 표현 불가",
                )
            }
        }
    }

    /** 정확 십진 타입(NUMERIC/DECIMAL) 원소가 double 왕복으로 값이 보존되는지. */
    private fun exactAsDouble(n: Number): Boolean {
        val d = n.toDouble()
        if (!d.isFinite()) return false
        val orig = when (n) {
            is java.math.BigDecimal -> n
            is java.math.BigInteger -> java.math.BigDecimal(n)
            is Long, is Int, is Short, is Byte -> java.math.BigDecimal.valueOf(n.toLong())
            else -> return true // Float/Double 은 이미 이진 부동소수 — 그대로 신뢰
        }
        return java.math.BigDecimal(d).compareTo(orig) == 0
    }

    @Throws(SQLException::class)
    private fun data(): Array<Any?> = elements ?: throw SQLException("Array was freed")

    /** carray INT64 바인딩용 — 정수 원소는 Number 로 받아 Long 으로 정규화 (null 불가: carray 정수엔 NULL 슬롯이 없다). */
    internal fun toLongArray(): LongArray = data().map {
        (it as? Number)?.toLong() ?: throw SQLException("INTEGER array element is not a number: $it")
    }.toLongArray()

    internal fun toDoubleArray(): DoubleArray = data().map {
        (it as? Number)?.toDouble() ?: throw SQLException("REAL array element is not a number: $it")
    }.toDoubleArray()

    internal fun toStringArray(): Array<String?> = data().map { it?.toString() }.toTypedArray()

    @Throws(SQLException::class)
    override fun getBaseTypeName(): String = baseTypeName

    @Throws(SQLException::class)
    override fun getBaseType(): Int = when (kind) {
        Kind.INT64 -> Types.BIGINT
        Kind.DOUBLE -> Types.DOUBLE
        Kind.TEXT -> Types.VARCHAR
    }

    @Throws(SQLException::class)
    override fun getArray(): Any = data().copyOf()

    @Throws(SQLException::class)
    override fun getArray(map: Map<String, Class<*>>?): Any = getArray()

    @Throws(SQLException::class)
    override fun getArray(index: Long, count: Int): Any {
        val d = data()
        val from = (index - 1).toInt()
        if (from < 0 || from > d.size) throw SQLException("index out of range: $index")
        return d.copyOfRange(from, minOf(from + count, d.size))
    }

    @Throws(SQLException::class)
    override fun getArray(index: Long, count: Int, map: Map<String, Class<*>>?): Any = getArray(index, count)

    @Throws(SQLException::class)
    override fun getResultSet(): ResultSet = throw SQLFeatureNotSupportedException("Array.getResultSet")

    @Throws(SQLException::class)
    override fun getResultSet(map: Map<String, Class<*>>?): ResultSet = getResultSet()

    @Throws(SQLException::class)
    override fun getResultSet(index: Long, count: Int): ResultSet = getResultSet()

    @Throws(SQLException::class)
    override fun getResultSet(index: Long, count: Int, map: Map<String, Class<*>>?): ResultSet = getResultSet()

    override fun free() {
        elements = null
    }
}
