/*
 * Copyright (c) 2021 Gauthier Roebroeck <gauthier.roebroeck@gmail.com>
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
package io.roastedroot.sqlite4j

import io.roastedroot.sqlite4j.core.Codes
import io.roastedroot.sqlite4j.core.DB
import java.sql.Connection
import java.sql.SQLException

/**
 * Provides an interface for creating SQLite user-defined collations.
 *
 * A subclass of `Collation` can be registered with `Collation.create()` and called by the name it
 * was given. All collations must implement `xCompare(String, String)`, which is called when SQLite
 * compares two strings using the custom collation.
 */
abstract class Collation {
    private var conn: SQLiteConnection? = null
    private var db: DB? = null

    /**
     * Called by SQLite as a custom collation to compare two strings.
     *
     * @param str1 the first string in the comparison
     * @param str2 the second string in the comparison
     * @return an integer that is negative, zero, or positive if the first string is less than,
     *     equal to, or greater than the second, respectively
     */
    abstract fun xCompare(str1: String, str2: String): Int

    companion object {
        /**
         * Registers a given collation with the connection.
         *
         * @param conn The connection.
         * @param name The name of the collation.
         * @param f The collation to register.
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun create(conn: Connection, name: String, f: Collation) {
            if (conn !is SQLiteConnection) {
                throw SQLException("connection must be to an SQLite db")
            }
            if (conn.isClosed) {
                throw SQLException("connection closed")
            }

            f.conn = conn
            val database = conn.database
            f.db = database

            if (database.create_collation(name, f) != Codes.SQLITE_OK) {
                throw SQLException("error creating collation")
            }
        }

        /**
         * Removes a named collation from the given connection.
         *
         * @param conn The connection to remove the collation from.
         * @param name The name of the collation.
         * @throws SQLException
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun destroy(conn: Connection, name: String) {
            if (conn !is SQLiteConnection) {
                throw SQLException("connection must be to an SQLite db")
            }
            conn.database.destroy_collation(name)
        }
    }
}
