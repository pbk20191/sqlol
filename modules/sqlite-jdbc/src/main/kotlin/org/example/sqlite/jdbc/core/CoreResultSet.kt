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
import org.example.sqlite.jdbc.core.SafeStmtPtr.SafePtrFunction
import org.example.sqlite.jdbc.core.SafeStmtPtr.SafePtrIntFunction
import java.sql.SQLException
import java.sql.Statement

/** Implements a JDBC ResultSet.  */
abstract class CoreResultSet
/**
 * Default constructor for a given statement.
 * 
 * @param stmt The statement.
 */ protected constructor(@JvmField protected val stmt: CoreStatement) : Codes {
    /** If the result set does not have any rows.  */
    @JvmField
    var emptyResultSet: Boolean = false

    /** If the result set is open. Doesn't mean it has results.  */
    @JvmField
    var open: Boolean = false

    /**
     * Checks the status of the result set.
     *
     * @return True if has results and can iterate them; false otherwise.
     */
    fun isOpen(): Boolean = open

    /** Maximum number of rows as set by a Statement  */
    @JvmField
    var maxRows: Long = 0

    /** if null, the RS is closed()  */
    @JvmField
    var cols: Array<String>? = null

    /** same as cols, but used by Meta interface  */
    @JvmField
    var colsMeta: Array<String>? = null

    @JvmField
    protected var meta: Array<BooleanArray>? = null

    /** 0 means no limit, must check against maxRows  */
    @JvmField
    protected var limitRows: Int = 0

    /** number of current row, starts at 1 (0 is for before loading data)  */
    @JvmField
    protected var row: Int = 0

    @JvmField
    protected var pastLastRow: Boolean = false

    /** last column accessed, for wasNull(). -1 if none  */
    @JvmField
    protected var lastCol: Int = 0

    @JvmField
    var closeStmt: Boolean = false
    protected var columnNameToIndex: MutableMap<String, Int>? = null

    protected val database: DB
        // INTERNAL FUNCTIONS ///////////////////////////////////////////
        get() = stmt.database

    protected val connectionConfig: SQLiteConnectionConfig
        get() = stmt.connectionConfig

    /**
     * @throws SQLException if ResultSet is not open.
     */
    @Throws(SQLException::class)
    protected fun checkOpen() {
        if (!this.open) {
            throw SQLException("ResultSet closed")
        }
    }

    /**
     * Takes col in [1,x] form, returns in [0,x-1] form
     * 
     * @param col
     * @return
     * @throws SQLException
     */
    @Throws(SQLException::class)
    fun checkCol(col: Int): Int {
        var col = col
        if (colsMeta == null) {
            throw SQLException("SQLite JDBC: inconsistent internal state")
        }
        if (col < 1 || col > colsMeta!!.size) {
            throw SQLException("column " + col + " out of bounds [1," + colsMeta!!.size + "]")
        }
        return --col
    }

    /**
     * Takes col in [1,x] form, marks it as last accessed and returns [0,x-1]
     * 
     * @param col
     * @return
     * @throws SQLException
     */
    @Throws(SQLException::class)
    protected fun markCol(col: Int): Int {
        var col = col
        checkCol(col)
        lastCol = col
        return --col
    }

    /**
     * @throws SQLException
     */
    @Throws(SQLException::class)
    fun checkMeta() {
        checkCol(1)
        if (meta == null) {
            meta = stmt.pointer!!.safeRun<Array<BooleanArray>> { obj, stmt ->
                obj.column_metadata(stmt)
            }
        }
    }

    @Throws(SQLException::class)
    open fun close() {
        cols = null
        colsMeta = null
        meta = null
        limitRows = 0
        row = 0
        pastLastRow = false
        lastCol = -1
        columnNameToIndex = null
        emptyResultSet = false

        if (stmt.pointer!!.isClosed() || (!this.open && !closeStmt)) {
            return
        }

        val db = stmt.database
        synchronized(db) {
            if (!stmt.pointer!!.isClosed()) {
                stmt.pointer!!.safeRunInt { obj, stmt -> obj.reset(stmt) }

                if (closeStmt) {
                    closeStmt = false // break recursive call
                    (stmt as Statement).close()
                }
            }
        }

        this.open = false
    }

    protected fun findColumnIndexInCache(col: String): Int? {
        if (columnNameToIndex == null) {
            return null
        }
        return columnNameToIndex!!.get(col)
    }

    protected fun addColumnIndexInCache(col: String, index: Int): Int {
        if (columnNameToIndex == null) {
            columnNameToIndex = HashMap<String, Int>(cols!!.size)
        }
        columnNameToIndex!!.put(col, index)
        return index
    }
}
