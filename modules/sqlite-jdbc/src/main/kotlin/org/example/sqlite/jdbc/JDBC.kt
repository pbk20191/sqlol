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
package org.example.sqlite.jdbc

import org.example.sqlite.jdbc.util.LoggerFactory
import java.sql.*
import java.util.*
import java.util.function.Supplier
import java.util.logging.Logger

class JDBC : Driver {
    /**
     * @see Driver.getMajorVersion
     */
    override fun getMajorVersion(): Int {
        return SQLiteJDBCLoader.majorVersion
    }

    /**
     * @see Driver.getMinorVersion
     */
    override fun getMinorVersion(): Int {
        return SQLiteJDBCLoader.minorVersion
    }

    /**
     * @see Driver.jdbcCompliant
     */
    override fun jdbcCompliant(): Boolean {
        return false
    }

    @Throws(SQLFeatureNotSupportedException::class)
    override fun getParentLogger(): Logger? {
        // TODO
        return null
    }

    /**
     * @see Driver.acceptsURL
     */
    override fun acceptsURL(url: String?): Boolean {
        return isValidURL(url)
    }

    /**
     * @see Driver.getPropertyInfo
     */
    @Throws(SQLException::class)
    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> {
        return SQLiteConfig.driverPropertyInfo
    }

    /**
     * @see Driver.connect
     */
    @Throws(SQLException::class)
    override fun connect(url: String, info: Properties?): Connection? {
        return createConnection(url, info)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(JDBC::class.java)
        const val PREFIX: String = "jdbc:sqlite:"

        init {
            try {
                DriverManager.registerDriver(JDBC())
            } catch (e: SQLException) {
                logger.error(Supplier { "Could not register driver" }, e)
            }
        }

        /**
         * Validates a URL
         * 
         * @param url
         * @return true if the URL is valid, false otherwise
         */
        fun isValidURL(url: String?): Boolean {
            return url != null && url.lowercase(Locale.getDefault()).startsWith(PREFIX)
        }

        /**
         * Gets the location to the database from a given URL.
         * 
         * @param url The URL to extract the location from.
         * @return The location to the database.
         */
        fun extractAddress(url: String): String {
            return url.substring(PREFIX.length)
        }

        /**
         * Creates a new database connection to a given URL. Delegates to
         * [SQLiteDataSource.createConnection], which is the single connection-creation entry point.
         *
         * @param url the URL
         * @param prop the properties
         * @return a Connection object that represents a connection to the URL
         * @throws SQLException
         * @see Driver.connect
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun createConnection(
            url: String, prop: Properties?
        ): SQLiteConnection? {
            return SQLiteDataSource.createConnection(url, prop)
        }
    }
}
