package org.example.sqlite

/** sqlite3_column_type() 가 반환하는 컬럼의 저장 클래스 */
enum class SqliteType(val code: Int) {
    INTEGER(1),
    FLOAT(2),
    TEXT(3),
    BLOB(4),
    NULL(5);

    companion object {
        fun fromCode(code: Int): SqliteType =
            entries.firstOrNull { it.code == code } ?: error("unknown column type code: $code")
    }
}
