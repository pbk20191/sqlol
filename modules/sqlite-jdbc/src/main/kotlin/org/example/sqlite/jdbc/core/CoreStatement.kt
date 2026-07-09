/*
 * Copyright (c) 2007 David Crawshaw <david@zentus.com>
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package org.example.sqlite.jdbc.core

import org.example.sqlite.jdbc.SQLiteConnectionConfig
import org.example.sqlite.jdbc.SQLiteConnection
import org.example.sqlite.jdbc.jdbc3.JDBC3Connection
import org.example.sqlite.jdbc.jdbc4.JDBC4ResultSet
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.regex.Pattern

abstract class CoreStatement protected constructor(@JvmField val conn: SQLiteConnection) : Codes {
    @JvmField
    protected val rs: CoreResultSet = JDBC4ResultSet(this)

    @JvmField
    var pointer: SafeStmtPtr? = null
    @JvmField
    var sql: String? = null

    @JvmField
    protected var batchPos: Int = 0
    @JvmField
    protected var batch: Array<Any?>? = null
    @JvmField
    protected var resultsWaiting: Boolean = false

    private var generatedKeysStat: Statement? = null
    private var generatedKeysRs: ResultSet? = null

    val database: DB
        get() = conn.database

    val connectionConfig: SQLiteConnectionConfig
        get() = conn.connectionConfig

    /**
     * @throws SQLException If the database is not opened.
     */
    @Throws(SQLException::class)
    protected fun checkOpen() {
        if (pointer!!.isClosed()) throw SQLException("statement is not executing")
    }

    @get:Throws(SQLException::class)
    val isOpen: Boolean
        /**
         * @return True if the database is opened; false otherwise.
         * @throws SQLException
         */
        get() = !pointer!!.isClosed()

    /**
     * Calls sqlite3_step() and sets up results. Expects a clean stmt.
     * 
     * @return True if the ResultSet has at least one row; false otherwise.
     * @throws SQLException If the given SQL statement is null or no database is open.
     */
    @Throws(SQLException::class)
    protected fun exec(): Boolean {
        if (sql == null) throw SQLException("SQLiteJDBC internal error: sql==null")
        if (rs.open) throw SQLException("SQLite JDBC internal error: rs.isOpen() on exec.")

        if (this.conn is JDBC3Connection) {
            this.conn.tryEnforceTransactionMode()
        }

        var success = false
        var rc = false
        try {
            rc = conn.database.execute(this, null)
            success = true
        } finally {
            notifyFirstStatementExecuted()
            resultsWaiting = rc
            if (!success) {
                this.pointer!!.close()
            }
        }

        return pointer!!.safeRunInt { obj, stmt -> obj.column_count(stmt) } != 0
    }

    /**
     * Executes SQL statement and throws SQLExceptions if the given SQL statement is null or no
     * database is open.
     * 
     * @param sql SQL statement.
     * @return True if the ResultSet has at least one row; false otherwise.
     * @throws SQLException If the given SQL statement is null or no database is open.
     */
    @Throws(SQLException::class)
    protected fun exec(sql: String): Boolean {
        if (sql == null) throw SQLException("SQLiteJDBC internal error: sql==null")
        if (rs.open) throw SQLException("SQLite JDBC internal error: rs.isOpen() on exec.")

        if (this.conn is JDBC3Connection) {
            this.conn.tryEnforceTransactionMode()
        }

        var rc = false
        var success = false
        try {
            rc = conn.database.execute(sql, conn.getAutoCommit())
            success = true
        } finally {
            notifyFirstStatementExecuted()
            resultsWaiting = rc
            if (!success && pointer != null) {
                pointer!!.close()
            }
        }

        return pointer!!.safeRunInt { obj, stmt -> obj.column_count(stmt) } != 0
    }

    @Throws(SQLException::class)
    protected fun internalClose() {
        if (this.pointer != null && !this.pointer!!.isClosed()) {
            if (conn.isClosed) throw DB.newSQLException(Codes.SQLITE_ERROR, "Connection is closed")

            rs.close()

            batch = null
            batchPos = 0
            val resp = this.pointer!!.close()

            if (resp != Codes.SQLITE_OK && resp != Codes.SQLITE_MISUSE) conn.database.throwex(resp)
        }
    }

    protected fun notifyFirstStatementExecuted() {
        conn.isFirstStatementExecuted = true
    }

    @Throws(SQLException::class)
    abstract fun executeQuery(sql: String, closeStmt: Boolean): ResultSet?

    @Throws(SQLException::class)
    protected fun checkIndex(index: Int) {
        if (batch == null) {
            throw SQLException("No parameter has been set yet")
        }
        if (index < 1 || index > batch!!.size) {
            throw SQLException("Parameter index is invalid")
        }
    }

    @Throws(SQLException::class)
    protected fun clearGeneratedKeys() {
        if (generatedKeysRs != null && !generatedKeysRs!!.isClosed()) {
            generatedKeysRs!!.close()
        }
        generatedKeysRs = null
        if (generatedKeysStat != null && !generatedKeysStat!!.isClosed()) {
            generatedKeysStat!!.close()
        }
        generatedKeysStat = null
    }

    /**
     * SQLite's last_insert_rowid() function is DB-specific. However, in this implementation we
     * ensure the Generated Key result set is statement-specific by executing the query immediately
     * after an insert operation is performed. The caller is simply responsible for calling
     * updateGeneratedKeys on the statement object right after execute in a synchronized(connection)
     * block.
     */
    @Throws(SQLException::class)
    fun updateGeneratedKeys() {
        if (conn.connectionConfig.isGetGeneratedKeys) {
            clearGeneratedKeys()
            if (sql != null && INSERT_PATTERN.matcher(sql).find()) {
                generatedKeysStat = conn.createStatement()
                generatedKeysRs = generatedKeysStat!!.executeQuery("SELECT last_insert_rowid();")
            }
        }
    }

    /**
     * This implementation uses SQLite's last_insert_rowid function to obtain the row ID. It cannot
     * provide multiple values when inserting multiple rows. Suggestion is to use a [RETURNING](https://www.sqlite.org/lang_returning.html) clause instead.
     *
     * @see Statement.getGeneratedKeys
     */
    @Throws(SQLException::class)
    open fun getGeneratedKeys(): ResultSet {
            // getGeneratedKeys is required to return an EmptyResult set if the statement
            // did not generate any keys. Thus, if the generateKeysResultSet is NULL, spin
            // up a new result set without any contents by issuing a query with a false where condition
            if (generatedKeysRs == null) {
                generatedKeysStat = conn.createStatement()
                generatedKeysRs = generatedKeysStat!!.executeQuery("SELECT 1 WHERE 1 = 2;")
            }
            return generatedKeysRs!!
        }

    companion object {
        // pattern for matching insert statements of the general format starting with INSERT or REPLACE.
        // CTEs used prior to the insert or replace keyword are also be permitted.
        private val INSERT_PATTERN: Pattern = Pattern.compile(
            "^\\s*(?:with\\s+.+\\(.+?\\))*\\s*(?:insert|replace)\\s*",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        )
    }
}
