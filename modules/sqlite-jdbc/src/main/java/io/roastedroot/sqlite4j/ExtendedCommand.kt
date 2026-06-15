// --------------------------------------
// sqlite-jdbc Project
//
// ExtendedCommand.java
// Since: Mar 12, 2010
//
// $URL$
// $Author$
// --------------------------------------
package io.roastedroot.sqlite4j

import io.roastedroot.sqlite4j.core.DB
import io.roastedroot.sqlite4j.core.DB.Companion.newSQLException
import java.sql.SQLException
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * parsing SQLite specific extension of SQL command
 * 
 * @author leo
 */
object ExtendedCommand {
    /**
     * Parses extended commands of "backup" or "restore" for SQLite database.
     * 
     * @param sql One of the extended commands:<br></br>
     * backup sourceDatabaseName to destinationFileName OR restore targetDatabaseName from
     * sourceFileName
     * @return BackupCommand object if the argument is a backup command; RestoreCommand object if
     * the argument is a restore command;
     * @throws SQLException
     */
    @JvmStatic
    @Throws(SQLException::class)
    fun parse(sql: String?): SQLExtension? {
        if (sql == null) return null
        if (sql.length > 5 && sql.substring(0, 6)
                .lowercase(Locale.getDefault()) == "backup"
        ) return BackupCommand.Companion.parse(sql)
        else if (sql.length > 6 && sql.substring(0, 7)
                .lowercase(Locale.getDefault()) == "restore"
        ) return RestoreCommand.parse(sql)

        return null
    }

    /**
     * Remove the quotation mark from string.
     * 
     * @param s String with quotation mark.
     * @return String with quotation mark removed.
     */
    @JvmStatic
    fun removeQuotation(s: String?): String? {
        if (s == null) return s

        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) return if (s.length >= 2) s.substring(
            1,
            s.length - 1
        ) else s
        else return s
    }

    interface SQLExtension {
        @Throws(SQLException::class)
        fun execute(db: DB)
    }

    class BackupCommand
    /**
     * Constructs a BackupCommand instance that backup the database to a target file.
     * 
     * @param srcDB Source database name.
     * @param destFile Target file name.
     */(@JvmField val srcDB: String, @JvmField val destFile: String) : SQLExtension {
        @Throws(SQLException::class)
        override fun execute(db: DB) {
            val rc = db.backup(srcDB, destFile, null)

            if (rc != SQLiteErrorCode.SQLITE_OK.code) {
                throw newSQLException(rc, "Backup failed")
            }
        }

        companion object {
            private val backupCmd: Pattern = Pattern.compile(
                "backup(\\s+(\"[^\"]*\"|'[^\']*\'|\\S+))?\\s+to\\s+(\"[^\"]*\"|'[^\']*\'|\\S+)",
                Pattern.CASE_INSENSITIVE
            )

            /**
             * Parses SQLite database backup command and creates a BackupCommand object.
             * 
             * @param sql SQLite database backup command.
             * @return BackupCommand object.
             * @throws SQLException
             */
            @Throws(SQLException::class)
            fun parse(sql: String?): BackupCommand {
                if (sql != null) {
                    val m: Matcher = backupCmd.matcher(sql)
                    if (m.matches()) {
                        var dbName = removeQuotation(m.group(2))
                        val dest = removeQuotation(m.group(3))
                        if (dbName == null || dbName.length == 0) dbName = "main"

                        return ExtendedCommand.BackupCommand(dbName, dest!!)
                    }
                }
                throw SQLException("syntax error: " + sql)
            }
        }
    }

    class RestoreCommand
    /**
     * Constructs a RestoreCommand instance that restores the database from a given source file.
     * 
     * @param targetDB Target database name
     * @param srcFile Source file name
     */(@JvmField val targetDB: String, @JvmField val srcFile: String) : SQLExtension {
        /**
         * @see SQLExtension.execute
         */
        @Throws(SQLException::class)
        override fun execute(db: DB) {
            val rc = db.restore(targetDB, srcFile, null)

            if (rc != SQLiteErrorCode.SQLITE_OK.code) {
                throw newSQLException(rc, "Restore failed")
            }
        }

        companion object {
            private val restoreCmd: Pattern = Pattern.compile(
                "restore(\\s+(\"[^\"]*\"|'[^\']*\'|\\S+))?\\s+from\\s+(\"[^\"]*\"|'[^\']*\'|\\S+)",
                Pattern.CASE_INSENSITIVE
            )

            /**
             * Parses SQLite database restore command and creates a RestoreCommand object.
             * 
             * @param sql SQLite restore backup command
             * @return RestoreCommand object.
             * @throws SQLException
             */
            @Throws(SQLException::class)
            fun parse(sql: String?): RestoreCommand {
                if (sql != null) {
                    val m = restoreCmd.matcher(sql)
                    if (m.matches()) {
                        var dbName = removeQuotation(m.group(2))
                        val dest = removeQuotation(m.group(3))
                        if (dbName == null || dbName.length == 0) dbName = "main"
                        return RestoreCommand(dbName, dest!!)
                    }
                }
                throw SQLException("syntax error: " + sql)
            }
        }
    }
}
