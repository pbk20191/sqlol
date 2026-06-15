package org.example.sqlite.jdbc

/** https://www.sqlite.org/c3ref/update_hook.html  */
interface SQLiteUpdateListener {
    enum class Type {
        INSERT,
        DELETE,
        UPDATE
    }

    fun onUpdate(type: Type, database: String, table: String, rowId: Long)
}
