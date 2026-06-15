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
package io.roastedroot.sqlite4j.core

import io.roastedroot.sqlite4j.SQLiteConnection
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
            if (getTables != null) {
                getTables!!.close()
            }
            if (getTableTypes != null) {
                getTableTypes!!.close()
            }
            if (getTypeInfo != null) {
                getTypeInfo!!.close()
            }
            if (getCatalogs != null) {
                getCatalogs!!.close()
            }
            if (getSchemas != null) {
                getSchemas!!.close()
            }
            if (getUDTs != null) {
                getUDTs!!.close()
            }
            if (getColumnsTblName != null) {
                getColumnsTblName!!.close()
            }
            if (getSuperTypes != null) {
                getSuperTypes!!.close()
            }
            if (getSuperTables != null) {
                getSuperTables!!.close()
            }
            if (getTablePrivileges != null) {
                getTablePrivileges!!.close()
            }
            if (getIndexInfo != null) {
                getIndexInfo!!.close()
            }
            if (getProcedures != null) {
                getProcedures!!.close()
            }
            if (getProcedureColumns != null) {
                getProcedureColumns!!.close()
            }
            if (getAttributes != null) {
                getAttributes!!.close()
            }
            if (getBestRowIdentifier != null) {
                getBestRowIdentifier!!.close()
            }
            if (getVersionColumns != null) {
                getVersionColumns!!.close()
            }
            if (getColumnPrivileges != null) {
                getColumnPrivileges!!.close()
            }

            getTables = null
            getTableTypes = null
            getTypeInfo = null
            getCatalogs = null
            getSchemas = null
            getUDTs = null
            getColumnsTblName = null
            getSuperTypes = null
            getSuperTables = null
            getTablePrivileges = null
            getIndexInfo = null
            getProcedures = null
            getProcedureColumns = null
            getAttributes = null
            getBestRowIdentifier = null
            getVersionColumns = null
            getColumnPrivileges = null
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
