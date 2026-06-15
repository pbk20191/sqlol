package org.example.sqlite.jdbc

import java.sql.Connection
import java.sql.SQLException

/** https://www.sqlite.org/c3ref/busy_handler.html */
abstract class BusyHandler {

    /**
     * https://www.sqlite.org/c3ref/busy_handler.html
     *
     * @param nbPrevInvok number of times that the busy handler has been invoked previously for the
     *     same locking event
     * @throws SQLException
     * @return If the busy callback returns 0, then no additional attempts are made to access the
     *     database and SQLITE_BUSY is returned to the application. If the callback returns
     *     non-zero, then another attempt is made to access the database and the cycle repeats.
     */
    @Throws(SQLException::class)
    abstract fun callback(nbPrevInvok: Int): Int

    companion object {
        /**
         * commit the busy handler for the connection.
         *
         * @param conn the SQLite connection
         * @param busyHandler the busyHandler
         * @throws SQLException
         */
        @Throws(SQLException::class)
        private fun commitHandler(conn: Connection, busyHandler: BusyHandler?) {
            if (conn !is SQLiteConnection) {
                throw SQLException("connection must be to an SQLite db")
            }
            if (conn.isClosed) {
                throw SQLException("connection closed")
            }
            conn.database.busy_handler(busyHandler)
        }

        /**
         * Sets a busy handler for the connection.
         *
         * @param conn the SQLite connection
         * @param busyHandler the busyHandler
         * @throws SQLException
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun setHandler(conn: Connection, busyHandler: BusyHandler?) {
            commitHandler(conn, busyHandler)
        }

        /**
         * Clears any busy handler registered with the connection.
         *
         * @param conn the SQLite connection
         * @throws SQLException
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun clearHandler(conn: Connection) {
            commitHandler(conn, null)
        }
    }
}
