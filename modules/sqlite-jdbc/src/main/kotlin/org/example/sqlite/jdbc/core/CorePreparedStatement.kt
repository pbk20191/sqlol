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

import org.example.sqlite.jdbc.SQLiteConfig.DateClass
import org.example.sqlite.jdbc.SQLiteConnection
import org.example.sqlite.jdbc.core.SafeStmtPtr.SafePtrFunction
import org.example.sqlite.jdbc.core.SafeStmtPtr.SafePtrIntFunction
import org.example.sqlite.jdbc.date.FastDateFormat
import org.example.sqlite.jdbc.jdbc3.JDBC3Connection
import org.example.sqlite.jdbc.jdbc4.JDBC4Statement
import java.sql.Date
import java.sql.SQLException
import java.util.*
import java.util.function.LongToIntFunction

abstract class CorePreparedStatement protected constructor(conn: SQLiteConnection, sql: String) : JDBC4Statement(conn) {
    @JvmField
    protected var columnCount: Int
    @JvmField
    protected var paramCount: Int
    @JvmField
    protected var batchQueryCount: Int

    /**
     * Constructs a prepared statement on a provided connection.
     * 
     * @param conn Connection on which to create the prepared statement.
     * @param sql The SQL script to prepare.
     * @throws SQLException
     */
    init {
        this.sql = sql
        val db = conn.database
        db.prepare(this)
        rs.colsMeta = pointer!!.safeRun<Array<String>, SQLException>(SafePtrFunction { obj: DB?, stmt: Long ->
            obj!!.column_names(stmt)
        })
        columnCount =
            pointer!!.safeRunInt<SQLException>(SafePtrIntFunction { obj: DB?, stmt: Long -> obj!!.column_count(stmt) })
        paramCount = pointer!!.safeRunInt<SQLException>(SafePtrIntFunction { obj: DB?, stmt: Long ->
            obj!!.bind_parameter_count(stmt)
        })
        batchQueryCount = 0
        batch = null
        batchPos = 0
    }

    /**
     * @see JDBC3Statement.executeBatch
     */
    @Throws(SQLException::class)
    override fun executeBatch(): IntArray {
        return Arrays.stream(executeLargeBatch()).mapToInt(LongToIntFunction { l: Long -> l.toInt() }).toArray()
    }

    /**
     * @see JDBC3Statement.executeLargeBatch
     */
    @Throws(SQLException::class)
    override fun executeLargeBatch(): LongArray {
        if (batchQueryCount == 0) {
            return longArrayOf()
        }

        if (this.conn is JDBC3Connection) {
            this.conn.tryEnforceTransactionMode()
        }

        return this.withConnectionTimeout<LongArray> {
            try {
                return@withConnectionTimeout conn.database
                    .executeBatch(
                        pointer!!, batchQueryCount, batch, conn.getAutoCommit()
                    )
            } finally {
                clearBatch()
            }
        }
    }

    /**
     * @see JDBC3Statement.clearBatch
     */
    @Throws(SQLException::class)
    override fun clearBatch() {
        super.clearBatch()
        batchQueryCount = 0
    }

    // PARAMETER FUNCTIONS //////////////////////////////////////////
    /**
     * Assigns the object value to the element at the specific position of array batch.
     * 
     * @param pos
     * @param value
     * @throws SQLException
     */
    @Throws(SQLException::class)
    protected fun batch(pos: Int, value: Any?) {
        checkOpen()
        if (batch == null) {
            batch = arrayOfNulls<Any>(paramCount)
        }
        batch!![batchPos + pos - 1] = value
    }

    /** Store the date in the user's preferred format (text, int, or real)  */
    @Throws(SQLException::class)
    protected fun setDateByMilliseconds(pos: Int, value: Long, calendar: Calendar) {
        val config = conn.connectionConfig
        when (config.dateClass) {
            DateClass.TEXT -> batch(
                pos,
                FastDateFormat.getInstance(
                    config.dateStringFormat, calendar.getTimeZone()
                )
                    .format(Date(value))
            )

            DateClass.REAL ->                 // long to Julian date
                batch(pos, (value / 86400000.0) + 2440587.5)

            else -> batch(pos, value / config.dateMultiplier)
        }
    }
}
