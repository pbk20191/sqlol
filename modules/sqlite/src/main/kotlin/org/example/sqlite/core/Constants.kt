package org.example.sqlite.core

/** SQLite 결과 코드 */
internal const val SQLITE_OK = 0
internal const val SQLITE_BUSY = 5
internal const val SQLITE_LOCKED = 6
internal const val SQLITE_NOMEM = 7
internal const val SQLITE_PROTOCOL = 15
internal const val SQLITE_ROW = 100
internal const val SQLITE_DONE = 101

/** sqlite3_column_type 저장 클래스 */
internal const val TYPE_INTEGER = 1
internal const val TYPE_FLOAT = 2
internal const val TYPE_TEXT = 3
internal const val TYPE_BLOB = 4
internal const val TYPE_NULL = 5

/** sqlite3_bind_text/blob 의 destructor 인자. ((sqlite3_destructor_type)-1) = 즉시 복사 */
internal const val SQLITE_TRANSIENT = -1
