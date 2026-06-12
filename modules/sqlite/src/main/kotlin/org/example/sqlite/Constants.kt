package org.example.sqlite

/** SQLite 결과 코드 */
internal const val SQLITE_OK = 0
internal const val SQLITE_ROW = 100
internal const val SQLITE_DONE = 101

/** sqlite3_bind_text/blob 의 destructor 인자. ((sqlite3_destructor_type)-1) = 즉시 복사 */
internal const val SQLITE_TRANSIENT = -1
