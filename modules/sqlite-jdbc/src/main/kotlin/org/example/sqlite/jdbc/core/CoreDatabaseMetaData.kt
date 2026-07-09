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

import org.example.sqlite.jdbc.SQLiteConnection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.regex.Pattern

abstract class CoreDatabaseMetaData protected constructor(conn: SQLiteConnection) : DatabaseMetaData {
    @JvmField
    protected var conn: SQLiteConnection?
    protected var getTables: PreparedStatement? = null
    @JvmField
    protected var getTableTypes: PreparedStatement? = null
    @JvmField
    protected var getTypeInfo: PreparedStatement? = null
    @JvmField
    protected var getCatalogs: PreparedStatement? = null
    @JvmField
    protected var getSchemas: PreparedStatement? = null
    @JvmField
    protected var getUDTs: PreparedStatement? = null
    protected var getColumnsTblName: PreparedStatement? = null
    @JvmField
    protected var getSuperTypes: PreparedStatement? = null
    @JvmField
    protected var getSuperTables: PreparedStatement? = null
    @JvmField
    protected var getTablePrivileges: PreparedStatement? = null
    protected var getIndexInfo: PreparedStatement? = null
    @JvmField
    protected var getProcedures: PreparedStatement? = null
    @JvmField
    protected var getProcedureColumns: PreparedStatement? = null
    @JvmField
    protected var getAttributes: PreparedStatement? = null
    @JvmField
    protected var getBestRowIdentifier: PreparedStatement? = null
    @JvmField
    protected var getVersionColumns: PreparedStatement? = null
    @JvmField
    protected var getColumnPrivileges: PreparedStatement? = null

    @get:Throws(SQLException::class)
    @get:Deprecated(
        """Not exactly sure what this function does, as it is not implementing any
          interface, and is not used anywhere in the code. Deprecated since 3.43.0.0."""
    )
    abstract val generatedKeys: ResultSet?

    /**
     * 열려 있는 커넥션. 닫힌 뒤 접근은 [SQLException] ("connection closed") — 서브클래스의
     * `conn!!` 남용을 대체하는 단일 게이트.
     *
     * @throws SQLException
     */
    protected val db: SQLiteConnection
        @Throws(SQLException::class)
        get() = conn ?: throw SQLException("connection closed")

    /**
     * @throws SQLException
     */
    @Throws(SQLException::class)
    protected fun checkOpen() {
        if (conn == null) {
            throw SQLException("connection closed")
        }
    }

    /**
     * @throws SQLException
     */
    @Synchronized
    @Throws(SQLException::class)
    fun close() {
        if (conn == null) {
            return
        }

        try {
            getTables?.close(); getTables = null
            getTableTypes?.close(); getTableTypes = null
            getTypeInfo?.close(); getTypeInfo = null
            getCatalogs?.close(); getCatalogs = null
            getSchemas?.close(); getSchemas = null
            getUDTs?.close(); getUDTs = null
            getColumnsTblName?.close(); getColumnsTblName = null
            getSuperTypes?.close(); getSuperTypes = null
            getSuperTables?.close(); getSuperTables = null
            getTablePrivileges?.close(); getTablePrivileges = null
            getIndexInfo?.close(); getIndexInfo = null
            getProcedures?.close(); getProcedures = null
            getProcedureColumns?.close(); getProcedureColumns = null
            getAttributes?.close(); getAttributes = null
            getBestRowIdentifier?.close(); getBestRowIdentifier = null
            getVersionColumns?.close(); getVersionColumns = null
            getColumnPrivileges?.close(); getColumnPrivileges = null
        } finally {
            conn = null
        }
    }

    /**
     * Applies SQL escapes for special characters in a given string.
     * 
     * @param val The string to escape.
     * @return The SQL escaped string.
     */
    protected fun escape(`val`: String): String {
        // TODO: this function is ugly, pass this work off to SQLite, then we
        //       don't have to worry about Unicode 4, other characters needing
        //       escaping, etc.
        val len = `val`.length
        val buf = StringBuilder(len)
        for (i in 0..<len) {
            if (`val`.get(i) == '\'') {
                buf.append('\'')
            }
            buf.append(`val`.get(i))
        }
        return buf.toString()
    }

    /**
     * Constructor that applies the Connection object.
     * 
     * @param conn Connection object.
     */
    init {
        this.conn = conn
    }

    /**
     * @see Object.finalize
     */
    @Throws(Throwable::class)
    protected open fun finalize() {
        close()
    }

    companion object {
        /**
         * Adds SQL string quotes to the given string.
         * 
         * @param tableName The string to quote.
         * @return The quoted string.
         */
        @JvmStatic
        protected fun quote(tableName: String?): String {
            if (tableName == null) {
                return "null"
            } else {
                return String.format("'%s'", tableName)
            }
        }

        // inner classes
        /** Pattern used to extract column order for an unnamed primary key.  */
        protected val PK_UNNAMED_PATTERN: Pattern = Pattern.compile(
            ".*\\sPRIMARY\\s+KEY\\s+\\((.*?,+.*?)\\).*",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )

        /** Pattern used to extract a named primary key.  */
        protected val PK_NAMED_PATTERN: Pattern = Pattern.compile(
            ".*\\sCONSTRAINT\\s+(.*?)\\s+PRIMARY\\s+KEY\\s+\\((.*?)\\).*",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
    }
}
