package org.example.sqlite.jdbc

import java.sql.Connection
import java.sql.SQLException

/** https://www.sqlite.org/c3ref/progress_handler.html */
abstract class ProgressHandler {
    @Throws(SQLException::class)
    abstract fun progress(): Int

    companion object {
        /**
         * Sets a progress handler for the connection.
         *
         * @param conn the SQLite connection
         * @param vmCalls the approximate number of virtual machine instructions that are evaluated
         *     between successive invocations of the progressHandler
         * @param progressHandler the progressHandler
         * @throws SQLException
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun setHandler(conn: Connection, vmCalls: Int, progressHandler: ProgressHandler?) {
            if (conn !is SQLiteConnection) {
                throw SQLException("connection must be to an SQLite db")
            }
            if (conn.isClosed) {
                throw SQLException("connection closed")
            }
            conn.database.register_progress_handler(vmCalls, progressHandler)
        }

        /**
         * Clears any progress handler registered with the connection.
         *
         * @param conn the SQLite connection
         * @throws SQLException
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun clearHandler(conn: Connection) {
            val sqliteConnection = conn as SQLiteConnection
            sqliteConnection.database.clear_progress_handler()
        }
    }
}
