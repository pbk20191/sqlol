package org.example.sqlite.jdbc

/** https://www.sqlite.org/c3ref/commit_hook.html */
interface SQLiteCommitListener {
    fun onCommit()

    fun onRollback()
}
