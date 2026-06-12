package org.example.sqlite

/**
 * prepared statement. 파라미터 바인딩(1-based)과 컬럼 접근(0-based)을 제공한다.
 * [close] 로 sqlite3_finalize 한다.
 */
class SqliteStmt internal constructor(
    private val n: Native,
    private val handle: Int,
    private val db: Int,
) : AutoCloseable {

    private val x get() = n.exports

    // ---- 바인딩 (인덱스는 1부터) ----
    fun bindNull(index: Int): SqliteStmt = apply { x.sqlite3BindNull(handle, index) }

    fun bindInt(index: Int, value: Int): SqliteStmt =
        apply { x.sqlite3BindInt(handle, index, value) }

    fun bindLong(index: Int, value: Long): SqliteStmt =
        apply { x.sqlite3BindInt64(handle, index, value) }

    fun bindDouble(index: Int, value: Double): SqliteStmt =
        apply { x.sqlite3BindDouble(handle, index, value) }

    fun bindText(index: Int, value: String): SqliteStmt = apply {
        val len = value.toByteArray(Charsets.UTF_8).size
        val ptr = n.cString(value)
        x.sqlite3BindText(handle, index, ptr, len, SQLITE_TRANSIENT)
        n.free(ptr)   // TRANSIENT 이므로 SQLite 가 즉시 복사 → 해제 안전
    }

    fun bindBlob(index: Int, value: ByteArray): SqliteStmt = apply {
        val ptr = n.writeBytes(value)
        x.sqlite3BindBlob(handle, index, ptr, value.size, SQLITE_TRANSIENT)
        n.free(ptr)
    }

    /** Kotlin 타입에 따라 적절한 bind* 로 디스패치 */
    fun bind(index: Int, value: Any?): SqliteStmt = when (value) {
        null -> bindNull(index)
        is Int -> bindInt(index, value)
        is Long -> bindLong(index, value)
        is Double -> bindDouble(index, value)
        is Float -> bindDouble(index, value.toDouble())
        is Boolean -> bindInt(index, if (value) 1 else 0)
        is String -> bindText(index, value)
        is ByteArray -> bindBlob(index, value)
        else -> error("지원하지 않는 bind 타입: ${value::class}")
    }

    // ---- 실행 ----
    /** 다음 행으로 진행. 행이 있으면 true, 끝이면 false */
    fun step(): Boolean = when (val rc = x.sqlite3Step(handle)) {
        SQLITE_ROW -> true
        SQLITE_DONE -> false
        else -> error("step rc=$rc: ${n.errmsg(db)}")
    }

    fun reset(): SqliteStmt = apply { x.sqlite3Reset(handle) }
    fun clearBindings(): SqliteStmt = apply { x.sqlite3ClearBindings(handle) }

    // ---- 컬럼 접근 (인덱스는 0부터) ----
    val columnCount: Int get() = x.sqlite3ColumnCount(handle)
    fun columnName(index: Int): String = n.readCString(x.sqlite3ColumnName(handle, index))
    fun columnType(index: Int): SqliteType = SqliteType.fromCode(x.sqlite3ColumnType(handle, index))
    fun isNull(index: Int): Boolean = columnType(index) == SqliteType.NULL

    fun getInt(index: Int): Int = x.sqlite3ColumnInt(handle, index)
    fun getLong(index: Int): Long = x.sqlite3ColumnInt64(handle, index)
    fun getDouble(index: Int): Double = x.sqlite3ColumnDouble(handle, index)

    fun getText(index: Int): String {
        val len = x.sqlite3ColumnBytes(handle, index)
        val ptr = x.sqlite3ColumnText(handle, index)
        return n.readString(ptr, len)
    }

    fun getBlob(index: Int): ByteArray {
        val len = x.sqlite3ColumnBytes(handle, index)
        val ptr = x.sqlite3ColumnBlob(handle, index)
        return n.readBytes(ptr, len)
    }

    /** 컬럼의 저장 타입에 맞는 Kotlin 객체 반환 (INTEGER→Long, FLOAT→Double, TEXT→String, BLOB→ByteArray, NULL→null) */
    fun getValue(index: Int): Any? = when (columnType(index)) {
        SqliteType.INTEGER -> getLong(index)
        SqliteType.FLOAT -> getDouble(index)
        SqliteType.TEXT -> getText(index)
        SqliteType.BLOB -> getBlob(index)
        SqliteType.NULL -> null
    }

    /** 현재 행 전체를 컬럼 순서대로 */
    fun row(): List<Any?> = (0 until columnCount).map { getValue(it) }

    override fun close() {
        x.sqlite3Finalize(handle)
    }
}
