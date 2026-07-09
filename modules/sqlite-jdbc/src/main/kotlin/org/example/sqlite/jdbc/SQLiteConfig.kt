/**
 * Copyright 2009 Taro L. Saito
 * 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 * --------------------------------------------------------------------------
 */
// --------------------------------------
// sqlite-jdbc Project
//
// SQLiteConfig.java
// Since: Dec 8, 2009
//
// $URL$
// $Author$
// --------------------------------------
package org.example.sqlite.jdbc

import java.sql.Connection
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.util.*

/**
 * SQLite Configuration
 * 
 * 
 * See also https://www.sqlite.org/pragma.html
 * 
 * @author leo
 */
class SQLiteConfig @JvmOverloads constructor(private val pragmaTable: Properties = Properties()) {
    /**
     * @return The open mode flags.
     */
    var openModeFlags: Int = 0x00
        private set

    private var busyTimeout = 0
    /**
     * @return true if explicit read only transactions are enabled
     */
    /**
     * Enable read only transactions after connection creation if explicit read only is true.
     * 
     * @param readOnly whether to enable explicit read only
     */
    var isExplicitReadOnly: Boolean

    private val defaultConnectionConfig: SQLiteConnectionConfig

    fun newConnectionConfig(): SQLiteConnectionConfig {
        return defaultConnectionConfig.copyConfig()
    }

    /**
     * Create a new JDBC connection using the current configuration
     * 
     * @return The connection.
     * @throws SQLException
     */
    @Throws(SQLException::class)
    fun createConnection(url: String): Connection? {
        return SQLiteDataSource.createConnection(url, toProperties())
    }

    /**
     * Configures a connection.
     * 
     * @param conn The connection to configure.
     * @throws SQLException
     */
    @Throws(SQLException::class)
    fun apply(conn: Connection) {
        val pragmaParams = HashSet<String>()
        for (each in Pragma.entries) {
            pragmaParams.add(each.pragmaName)
        }

        if (conn is SQLiteConnection) {
            val sqliteConn = conn
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_ATTACHED,
                parseLimitPragma(Pragma.LIMIT_ATTACHED, DEFAULT_MAX_ATTACHED)
            )
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_COLUMN,
                parseLimitPragma(Pragma.LIMIT_COLUMN, DEFAULT_MAX_COLUMN)
            )
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_COMPOUND_SELECT,
                parseLimitPragma(Pragma.LIMIT_COMPOUND_SELECT, -1)
            )
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_EXPR_DEPTH,
                parseLimitPragma(Pragma.LIMIT_EXPR_DEPTH, -1)
            )
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_FUNCTION_ARG,
                parseLimitPragma(Pragma.LIMIT_FUNCTION_ARG, DEFAULT_MAX_FUNCTION_ARG)
            )
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_LENGTH,
                parseLimitPragma(Pragma.LIMIT_LENGTH, DEFAULT_MAX_LENGTH)
            )
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_LIKE_PATTERN_LENGTH,
                parseLimitPragma(Pragma.LIMIT_LIKE_PATTERN_LENGTH, -1)
            )
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_SQL_LENGTH,
                parseLimitPragma(Pragma.LIMIT_SQL_LENGTH, DEFAULT_MAX_SQL_LENGTH)
            )
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_TRIGGER_DEPTH,
                parseLimitPragma(Pragma.LIMIT_TRIGGER_DEPTH, -1)
            )
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_VARIABLE_NUMBER,
                parseLimitPragma(Pragma.LIMIT_VARIABLE_NUMBER, -1)
            )
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_VDBE_OP, parseLimitPragma(Pragma.LIMIT_VDBE_OP, -1)
            )
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_WORKER_THREADS,
                parseLimitPragma(Pragma.LIMIT_WORKER_THREADS, -1)
            )
            sqliteConn.setLimit(
                SQLiteLimits.SQLITE_LIMIT_PAGE_COUNT,
                parseLimitPragma(Pragma.LIMIT_PAGE_COUNT, DEFAULT_MAX_PAGE_COUNT)
            )
        }

        pragmaParams.remove(Pragma.OPEN_MODE.pragmaName)
        pragmaParams.remove(Pragma.SHARED_CACHE.pragmaName)
        pragmaParams.remove(Pragma.LOAD_EXTENSION.pragmaName)
        pragmaParams.remove(Pragma.DATE_PRECISION.pragmaName)
        pragmaParams.remove(Pragma.DATE_CLASS.pragmaName)
        pragmaParams.remove(Pragma.DATE_STRING_FORMAT.pragmaName)
        pragmaParams.remove(Pragma.PASSWORD.pragmaName)
        pragmaParams.remove(Pragma.HEXKEY_MODE.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_ATTACHED.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_COLUMN.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_COMPOUND_SELECT.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_EXPR_DEPTH.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_FUNCTION_ARG.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_LENGTH.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_LIKE_PATTERN_LENGTH.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_SQL_LENGTH.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_TRIGGER_DEPTH.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_VARIABLE_NUMBER.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_VDBE_OP.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_WORKER_THREADS.pragmaName)
        pragmaParams.remove(Pragma.LIMIT_PAGE_COUNT.pragmaName)

        // exclude this "fake" pragma from execution
        pragmaParams.remove(Pragma.JDBC_EXPLICIT_READONLY.pragmaName)
        pragmaParams.remove(Pragma.JDBC_GET_GENERATED_KEYS.pragmaName)

        val stat = conn.createStatement()
        try {
            if (pragmaTable.containsKey(Pragma.PASSWORD.pragmaName)) {
                val password = pragmaTable.getProperty(Pragma.PASSWORD.pragmaName)
                if (password != null && !password.isEmpty()) {
                    val hexkeyMode = pragmaTable.getProperty(Pragma.HEXKEY_MODE.pragmaName)
                    val passwordPragma: String?
                    if (HexKeyMode.SSE.name.equals(hexkeyMode, ignoreCase = true)) {
                        passwordPragma = "pragma hexkey = '%s'"
                    } else if (HexKeyMode.SQLCIPHER.name.equals(hexkeyMode, ignoreCase = true)) {
                        passwordPragma = "pragma key = \"x'%s'\""
                    } else {
                        passwordPragma = "pragma key = '%s'"
                    }
                    stat!!.execute(String.format(passwordPragma, password.replace("'", "''")))
                    stat.execute("select 1 from sqlite_schema")
                }
            }

            for (each in pragmaTable.keys) {
                val key: String? = each.toString()
                if (!pragmaParams.contains(key)) {
                    continue
                }

                val value = pragmaTable.getProperty(key)
                if (value != null) {
                    stat!!.execute(String.format("pragma %s=%s", key, value))
                }
            }
        } finally {
            if (stat != null) {
                stat.close()
            }
        }
    }

    /**
     * Sets a pragma to the given boolean value.
     * 
     * @param pragma The pragma to set.
     * @param flag The boolean value.
     */
    private fun set(pragma: Pragma, flag: Boolean) {
        setPragma(pragma, flag.toString())
    }

    /**
     * Sets a pragma to the given int value.
     * 
     * @param pragma The pragma to set.
     * @param num The int value.
     */
    private fun set(pragma: Pragma, num: Int) {
        setPragma(pragma, num.toString())
    }

    /**
     * Checks if the provided value is the default for a given pragma.
     * 
     * @param pragma The pragma on which to check.
     * @param defaultValue The value to check for.
     * @return True if the given value is the default value; false otherwise.
     */
    private fun getBoolean(pragma: Pragma, defaultValue: String): Boolean {
        return pragmaTable.getProperty(pragma.pragmaName, defaultValue).toBoolean()
    }

    /**
     * Retrieves a pragma integer value.
     * 
     * @param pragma The pragma.
     * @param defaultValue The default value.
     * @return The value of the pragma or defaultValue.
     */
    private fun parseLimitPragma(pragma: Pragma, defaultValue: Int): Int {
        if (!pragmaTable.containsKey(pragma.pragmaName)) {
            return defaultValue
        }
        val valueString = pragmaTable.getProperty(pragma.pragmaName)
        try {
            return valueString.toInt()
        } catch (ex: NumberFormatException) {
            return defaultValue
        }
    }

    val isEnabledSharedCache: Boolean
        /**
         * Checks if the shared cache option is turned on.
         * 
         * @return True if turned on; false otherwise.
         */
        get() = getBoolean(Pragma.SHARED_CACHE, "false")

    val isEnabledLoadExtension: Boolean
        /**
         * Checks if the load extension option is turned on.
         * 
         * @return True if turned on; false otherwise.
         */
        get() = getBoolean(Pragma.LOAD_EXTENSION, "false")

    /**
     * Sets a pragma's value.
     * 
     * @param pragma The pragma to change.
     * @param value The value to set it to.
     */
    fun setPragma(pragma: Pragma, value: String) {
        pragmaTable.put(pragma.pragmaName, value)
    }

    /**
     * Convert this configuration into a Properties object, which can be passed to the [ ][DriverManager.getConnection].
     * 
     * @return The property object.
     */
    fun toProperties(): Properties {
        pragmaTable.setProperty(Pragma.OPEN_MODE.pragmaName, openModeFlags.toString())
        pragmaTable.setProperty(
            Pragma.TRANSACTION_MODE.pragmaName,
            defaultConnectionConfig.transactionMode.value
        )
        pragmaTable.setProperty(
            Pragma.DATE_CLASS.pragmaName, defaultConnectionConfig.dateClass.value
        )
        pragmaTable.setProperty(
            Pragma.DATE_PRECISION.pragmaName,
            defaultConnectionConfig.datePrecision.value
        )
        pragmaTable.setProperty(
            Pragma.DATE_STRING_FORMAT.pragmaName,
            defaultConnectionConfig.dateStringFormat
        )
        pragmaTable.setProperty(
            Pragma.JDBC_EXPLICIT_READONLY.pragmaName, if (this.isExplicitReadOnly) "true" else "false"
        )
        pragmaTable.setProperty(
            Pragma.JDBC_GET_GENERATED_KEYS.pragmaName,
            if (defaultConnectionConfig.isGetGeneratedKeys) "true" else "false"
        )
        return pragmaTable
    }

    internal object OnOff {
        val Values = arrayOf<String>("true", "false")
    }

    /**
     * Creates an SQLite configuration object using values from the given property object.
     * 
     * @param prop The properties to apply to the configuration.
     */
    /** Default constructor.  */
    init {
        val openMode = pragmaTable.getProperty(Pragma.OPEN_MODE.pragmaName)
        if (openMode != null) {
            this.openModeFlags = openMode.toInt()
        } else {
            // set the default open mode of SQLite3
            setOpenMode(SQLiteOpenMode.READWRITE)
            setOpenMode(SQLiteOpenMode.CREATE)
        }
        // Shared Cache
        setSharedCache(
            pragmaTable.getProperty(Pragma.SHARED_CACHE.pragmaName, "false").toBoolean()
        )
        // Enable URI filenames
        setOpenMode(SQLiteOpenMode.OPEN_URI)

        setBusyTimeout(
            pragmaTable.getProperty(Pragma.BUSY_TIMEOUT.pragmaName, "3000").toInt()
        )
        this.defaultConnectionConfig = SQLiteConnectionConfig.fromPragmaTable(pragmaTable)
        this.isExplicitReadOnly = pragmaTable.getProperty(Pragma.JDBC_EXPLICIT_READONLY.pragmaName, "false").toBoolean()
    }

    enum class Pragma(@JvmField val pragmaName: String, val description: String?, val choices: Array<String>?) {
        // Parameters requiring SQLite3 API invocation
        OPEN_MODE("open_mode", "Database open-mode flag", null),
        SHARED_CACHE(
            "shared_cache",
            "Enable SQLite Shared-Cache mode, native driver only",
            OnOff.Values
        ),
        LOAD_EXTENSION(
            "enable_load_extension",
            "Enable SQLite load_extension() function, native driver only",
            OnOff.Values
        ),

        // Pragmas that can be set after opening the database
        CACHE_SIZE(
            "cache_size",
            "Maximum number of database disk pages that SQLite will hold in memory at once per"
                    + " open database file",
            null
        ),
        MMAP_SIZE(
            "mmap_size",
            "Maximum number of bytes that are set aside for memory-mapped I/O on a single"
                    + " database",
            null
        ),
        CASE_SENSITIVE_LIKE(
            "case_sensitive_like",
            "Installs a new application-defined LIKE function that is either case sensitive or"
                    + " insensitive depending on the value",
            OnOff.Values
        ),
        COUNT_CHANGES("count_changes", "Deprecated", OnOff.Values),
        DEFAULT_CACHE_SIZE("default_cache_size", "Deprecated", null),
        DEFER_FOREIGN_KEYS(
            "defer_foreign_keys",
            ("When the defer_foreign_keys PRAGMA is on, enforcement of all foreign key"
                    + " constraints is delayed until the outermost transaction is committed. The"
                    + " defer_foreign_keys pragma defaults to OFF so that foreign key constraints"
                    + " are only deferred if they are created as \"DEFERRABLE INITIALLY DEFERRED\"."
                    + " The defer_foreign_keys pragma is automatically switched off at each COMMIT"
                    + " or ROLLBACK. Hence, the defer_foreign_keys pragma must be separately"
                    + " enabled for each transaction. This pragma is only meaningful if foreign key"
                    + " constraints are enabled, of course."),
            OnOff.Values
        ),
        EMPTY_RESULT_CALLBACKS("empty_result_callback", "Deprecated", OnOff.Values),
        ENCODING(
            "encoding",
            "Set the encoding that the main database will be created with if it is created by"
                    + " this session",
            PragmaUtil.toStringArray(Encoding.entries.toTypedArray())
        ),
        FOREIGN_KEYS(
            "foreign_keys", "Set the enforcement of foreign key constraints", OnOff.Values
        ),
        FULL_COLUMN_NAMES("full_column_names", "Deprecated", OnOff.Values),
        FULL_SYNC(
            "fullsync",
            "Whether or not the F_FULLFSYNC syncing method is used on systems that support it."
                    + " Only Mac OS X supports F_FULLFSYNC.",
            OnOff.Values
        ),
        INCREMENTAL_VACUUM(
            "incremental_vacuum",
            ("Causes up to N pages to be removed from the freelist. The database file is"
                    + " truncated by the same amount. The incremental_vacuum pragma has no effect"
                    + " if the database is not in auto_vacuum=incremental mode or if there are no"
                    + " pages on the freelist. If there are fewer than N pages on the freelist, or"
                    + " if N is less than 1, or if the \"(N)\" argument is omitted, then the entire"
                    + " freelist is cleared."),
            null
        ),
        JOURNAL_MODE(
            "journal_mode",
            "Set the journal mode for databases associated with the current database"
                    + " connection",
            PragmaUtil.toStringArray(JournalMode.entries.toTypedArray())
        ),
        JOURNAL_SIZE_LIMIT(
            "journal_size_limit",
            "Limit the size of rollback-journal and WAL files left in the file-system after"
                    + " transactions or checkpoints",
            null
        ),
        LEGACY_ALTER_TABLE("legacy_alter_table", "Use legacy alter table behavior", OnOff.Values),
        LEGACY_FILE_FORMAT("legacy_file_format", "No-op", OnOff.Values),
        LOCKING_MODE(
            "locking_mode",
            "Set the database connection locking-mode",
            PragmaUtil.toStringArray(LockingMode.entries.toTypedArray())
        ),
        PAGE_SIZE(
            "page_size",
            "Set the page size of the database. The page size must be a power of two between"
                    + " 512 and 65536 inclusive.",
            null
        ),
        MAX_PAGE_COUNT(
            "max_page_count", "Set the maximum number of pages in the database file", null
        ),
        READ_UNCOMMITTED("read_uncommitted", "Set READ UNCOMMITTED isolation", OnOff.Values),
        RECURSIVE_TRIGGERS(
            "recursive_triggers", "Set the recursive trigger capability", OnOff.Values
        ),
        REVERSE_UNORDERED_SELECTS(
            "reverse_unordered_selects",
            "When enabled, this PRAGMA causes many SELECT statements without an ORDER BY clause"
                    + " to emit their results in the reverse order from what they normally would",
            OnOff.Values
        ),
        SECURE_DELETE(
            "secure_delete",
            "When secure_delete is on, SQLite overwrites deleted content with zeros",
            arrayOf<String>("true", "false", "fast")
        ),
        SHORT_COLUMN_NAMES("short_column_names", "Deprecated", OnOff.Values),
        SYNCHRONOUS(
            "synchronous",
            "Set the \"synchronous\" flag",
            PragmaUtil.toStringArray(SynchronousMode.entries.toTypedArray())
        ),
        TEMP_STORE(
            "temp_store",
            ("When temp_store is DEFAULT (0), the compile-time C preprocessor macro"
                    + " SQLITE_TEMP_STORE is used to determine where temporary tables and indices"
                    + " are stored. When temp_store is MEMORY (2) temporary tables and indices are"
                    + " kept as if they were in pure in-memory databases. When temp_store is FILE"
                    + " (1) temporary tables and indices are stored in a file. The"
                    + " temp_store_directory pragma can be used to specify the directory containing"
                    + " temporary files when FILE is specified. When the temp_store setting is"
                    + " changed, all existing temporary tables, indices, triggers, and views are"
                    + " immediately deleted."),
            PragmaUtil.toStringArray(TempStore.entries.toTypedArray())
        ),
        TEMP_STORE_DIRECTORY("temp_store_directory", "Deprecated", null),
        USER_VERSION(
            "user_version",
            ("Set the value of the user-version integer at offset 60 in the database header. The"
                    + " user-version is an integer that is available to applications to use however"
                    + " they want. SQLite makes no use of the user-version itself."),
            null
        ),
        APPLICATION_ID(
            "application_id",
            ("Set the 32-bit signed big-endian \"Application ID\" integer located at offset 68"
                    + " into the database header. Applications that use SQLite as their application"
                    + " file-format should set the Application ID integer to a unique integer so"
                    + " that utilities such as file(1) can determine the specific file type rather"
                    + " than just reporting \"SQLite3 Database\""),
            null
        ),

        // Limits
        LIMIT_LENGTH(
            "limit_length",
            "The maximum size of any string or BLOB or table row, in bytes.",
            null
        ),
        LIMIT_SQL_LENGTH(
            "limit_sql_length", "The maximum length of an SQL statement, in bytes.", null
        ),
        LIMIT_COLUMN(
            "limit_column",
            ("The maximum number of columns in a table definition or in the result set of a"
                    + " SELECT or the maximum number of columns in an index or in an ORDER BY or"
                    + " GROUP BY clause."),
            null
        ),
        LIMIT_EXPR_DEPTH(
            "limit_expr_depth", "The maximum depth of the parse tree on any expression.", null
        ),
        LIMIT_COMPOUND_SELECT(
            "limit_compound_select",
            "The maximum number of terms in a compound SELECT statement.",
            null
        ),
        LIMIT_VDBE_OP(
            "limit_vdbe_op",
            ("The maximum number of instructions in a virtual machine program used to implement"
                    + " an SQL statement. If sqlite3_prepare_v2() or the equivalent tries to"
                    + " allocate space for more than this many opcodes in a single prepared"
                    + " statement, an SQLITE_NOMEM error is returned."),
            null
        ),
        LIMIT_FUNCTION_ARG(
            "limit_function_arg", "The maximum number of arguments on a function.", null
        ),
        LIMIT_ATTACHED("limit_attached", "The maximum number of attached databases.", null),
        LIMIT_LIKE_PATTERN_LENGTH(
            "limit_like_pattern_length",
            "The maximum length of the pattern argument to the LIKE or GLOB operators.",
            null
        ),
        LIMIT_VARIABLE_NUMBER(
            "limit_variable_number",
            "The maximum index number of any parameter in an SQL statement.",
            null
        ),
        LIMIT_TRIGGER_DEPTH(
            "limit_trigger_depth", "The maximum depth of recursion for triggers.", null
        ),
        LIMIT_WORKER_THREADS(
            "limit_worker_threads",
            "The maximum number of auxiliary worker threads that a single prepared statement"
                    + " may start.",
            null
        ),
        LIMIT_PAGE_COUNT(
            "limit_page_count",
            "The maximum number of pages allowed in a single database file.",
            null
        ),

        // Others
        TRANSACTION_MODE(
            "transaction_mode",
            "Set the transaction mode",
            PragmaUtil.toStringArray(TransactionMode.entries.toTypedArray())
        ),
        DATE_PRECISION(
            "date_precision",
            ("\"seconds\": Read and store integer dates as seconds from the Unix Epoch (SQLite"
                    + " standard).\n"
                    + "\"milliseconds\": (DEFAULT) Read and store integer dates as milliseconds"
                    + " from the Unix Epoch (Java standard)."),
            PragmaUtil.toStringArray(DatePrecision.entries.toTypedArray())
        ),
        DATE_CLASS(
            "date_class",
            ("\"integer\": (Default) store dates as number of seconds or milliseconds from the"
                    + " Unix Epoch\n"
                    + "\"text\": store dates as a string of text\n"
                    + "\"real\": store dates as Julian Dates"),
            PragmaUtil.toStringArray(DateClass.entries.toTypedArray())
        ),
        DATE_STRING_FORMAT(
            "date_string_format",
            "Format to store and retrieve dates stored as text. Defaults to \"yyyy-MM-dd"
                    + " HH:mm:ss.SSS\"",
            null
        ),
        BUSY_TIMEOUT(
            "busy_timeout",
            "Sets a busy handler that sleeps for a specified amount of time when a table is"
                    + " locked",
            null
        ),
        HEXKEY_MODE(
            "hexkey_mode",
            "Mode of the secret key",
            PragmaUtil.toStringArray(HexKeyMode.entries.toTypedArray())
        ),
        PASSWORD("password", "Database password", null),

        // extensions: "fake" pragmas to allow conformance with JDBC
        JDBC_EXPLICIT_READONLY(
            "jdbc.explicit_readonly", "Set explicit read only transactions", null
        ),
        JDBC_GET_GENERATED_KEYS(
            "jdbc.get_generated_keys", "Enable retrieval of generated keys", OnOff.Values
        );

        @JvmOverloads
        constructor(pragmaName: String, choices: Array<String>? = null) : this(pragmaName, null, choices)


    }
    object PragmaUtil {
        /**
         * Convert the given enum values to a string array
         *
         * @param list Array if PragmaValue.
         * @return String array of Enum values
         */
        internal fun toStringArray(list: Array<PragmaValue>): Array<String> {
            val buffer = ArrayList<String>()
            buffer.ensureCapacity(list.size)
            for (i in list.indices) {
                buffer.add(list[i].value!!)
                buffer[i] = list[i].value!!
            }

            return buffer.toTypedArray()
        }
    }

    /**
     * Sets the open mode flags.
     * 
     * @param mode The open mode.
     * @see [https://www.sqlite.org/c3ref/c_open_autoproxy.html](https://www.sqlite.org/c3ref/c_open_autoproxy.html)
     */
    fun setOpenMode(mode: SQLiteOpenMode) {
        this.openModeFlags = this.openModeFlags or mode.flag
    }

    /**
     * Re-sets the open mode flags.
     * 
     * @param mode The open mode.
     * @see [https://www.sqlite.org/c3ref/c_open_autoproxy.html](https://www.sqlite.org/c3ref/c_open_autoproxy.html)
     */
    fun resetOpenMode(mode: SQLiteOpenMode) {
        this.openModeFlags = this.openModeFlags and mode.flag.inv()
    }

    /**
     * Enables or disables the sharing of the database cache and schema data structures between
     * connections to the same database.
     * 
     * @param enable True to enable; false to disable.
     * @see [www.sqlite.org/c3ref/enable_shared_cache.html](https://www.sqlite.org/c3ref/enable_shared_cache.html)
     */
    fun setSharedCache(enable: Boolean) {
        set(Pragma.SHARED_CACHE, enable)
    }

    /**
     * Enables or disables extension loading.
     * 
     * @param enable True to enable; false to disable.
     * @see [www.sqlite.org/c3ref/load_extension.html](https://www.sqlite.org/c3ref/load_extension.html)
     */
    fun enableLoadExtension(enable: Boolean) {
        set(Pragma.LOAD_EXTENSION, enable)
    }

    /**
     * Sets the read-write mode for the database.
     * 
     * @param readOnly True for read-only; otherwise read-write.
     */
    fun setReadOnly(readOnly: Boolean) {
        if (readOnly) {
            setOpenMode(SQLiteOpenMode.READONLY)
            resetOpenMode(SQLiteOpenMode.CREATE)
            resetOpenMode(SQLiteOpenMode.READWRITE)
        } else {
            setOpenMode(SQLiteOpenMode.READWRITE)
            setOpenMode(SQLiteOpenMode.CREATE)
            resetOpenMode(SQLiteOpenMode.READONLY)
        }
    }

    /**
     * Changes the maximum number of database disk pages that SQLite will hold in memory at once per
     * open database file.
     * 
     * @param numberOfPages Cache size in number of pages.
     * @see [www.sqlite.org/pragma.html.pragma_cache_size](https://www.sqlite.org/pragma.html.pragma_cache_size)
     */
    fun setCacheSize(numberOfPages: Int) {
        set(Pragma.CACHE_SIZE, numberOfPages)
    }

    /**
     * Enables or disables case sensitive for the LIKE operator.
     * 
     * @param enable True to enable; false to disable.
     * @see [www.sqlite.org/pragma.html.pragma_case_sensitive_like](https://www.sqlite.org/pragma.html.pragma_case_sensitive_like)
     */
    fun enableCaseSensitiveLike(enable: Boolean) {
        set(Pragma.CASE_SENSITIVE_LIKE, enable)
    }

    /**
     * @param enable True to enable; false to disable.
     * @see [www.sqlite.org/pragma.html.pragma_count_changes](https://www.sqlite.org/pragma.html.pragma_count_changes)
     */
    @Deprecated(
        """Enables or disables the count-changes flag. When enabled, INSERT, UPDATE and
          DELETE statements return the number of rows they modified.
      """
    )
    fun enableCountChanges(enable: Boolean) {
        set(Pragma.COUNT_CHANGES, enable)
    }

    /**
     * Sets the suggested maximum number of database disk pages that SQLite will hold in memory at
     * once per open database file. The cache size set here persists across database connections.
     * 
     * @param numberOfPages Cache size in number of pages.
     * @see [www.sqlite.org/pragma.html.pragma_cache_size](https://www.sqlite.org/pragma.html.pragma_cache_size)
     */
    fun setDefaultCacheSize(numberOfPages: Int) {
        set(Pragma.DEFAULT_CACHE_SIZE, numberOfPages)
    }

    /**
     * Defers enforcement of foreign key constraints until the outermost transaction is committed.
     * 
     * @param enable True to enable; false to disable;
     * @see [https://www.sqlite.org/pragma.html.pragma_defer_foreign_keys](https://www.sqlite.org/pragma.html.pragma_defer_foreign_keys)
     */
    fun deferForeignKeys(enable: Boolean) {
        set(Pragma.DEFER_FOREIGN_KEYS, enable)
    }

    /**
     * @param enable True to enable; false to disable. false.
     * @see [https://www.sqlite.org/pragma.html.pragma_empty_result_callbacks](https://www.sqlite.org/pragma.html.pragma_empty_result_callbacks)
     */
    @Deprecated(
        """Enables or disables the empty_result_callbacks flag.
      """
    )
    fun enableEmptyResultCallBacks(enable: Boolean) {
        set(Pragma.EMPTY_RESULT_CALLBACKS, enable)
    }

    /**
     * The common interface for retrieving the available pragma parameter values.
     * 
     * @author leo
     */
    internal interface PragmaValue {
        val value: String?
    }

    enum class Encoding : PragmaValue {
        UTF8("'UTF-8'"),
        UTF16("'UTF-16'"),
        UTF16_LITTLE_ENDIAN("'UTF-16le'"),
        UTF16_BIG_ENDIAN("'UTF-16be'"),
        UTF_8(UTF8),  // UTF-8
        UTF_16(UTF16),  // UTF-16
        UTF_16LE(UTF16_LITTLE_ENDIAN),  // UTF-16le
        UTF_16BE(UTF16_BIG_ENDIAN); // UTF-16be

        val typeName: String

        constructor(typeName: String) {
            this.typeName = typeName
        }

        constructor(encoding: Encoding) {
            this.typeName = encoding.value
        }

        override val value get() = typeName

        companion object {
            fun getEncoding(value: String): Encoding {
                return valueOf(value.replace("-".toRegex(), "_").uppercase(Locale.getDefault()))
            }
        }
    }

    enum class JournalMode : PragmaValue {
        DELETE,
        TRUNCATE,
        PERSIST,
        MEMORY,
        WAL,
        OFF;

        override val value get() = name
    }

    /**
     * Sets the text encoding used by the main database.
     * 
     * @param encoding One of [Encoding]
     * @see [www.sqlite.org/pragma.html.pragma_encoding](https://www.sqlite.org/pragma.html.pragma_encoding)
     */
    fun setEncoding(encoding: Encoding) {
        setPragma(Pragma.ENCODING, encoding.typeName)
    }

    /**
     * Whether to enforce foreign key constraints. This setting affects the execution of all
     * statements prepared using the database connection, including those prepared before the
     * setting was changed.
     * 
     * @param enforce True to enable; false to disable.
     * @see [www.sqlite.org/pragma.html.pragma_foreign_keys](https://www.sqlite.org/pragma.html.pragma_foreign_keys)
     */
    fun enforceForeignKeys(enforce: Boolean) {
        set(Pragma.FOREIGN_KEYS, enforce)
    }

    /**
     * @param enable True to enable; false to disable.
     * @see [www.sqlite.org/pragma.html.pragma_full_column_names](https://www.sqlite.org/pragma.html.pragma_full_column_names)
     */
    @Deprecated(
        """Enables or disables the full_column_name flag. This flag together with the
          short_column_names flag determine the way SQLite assigns names to result columns of
          SELECT statements.
      """
    )
    fun enableFullColumnNames(enable: Boolean) {
        set(Pragma.FULL_COLUMN_NAMES, enable)
    }

    /**
     * Enables or disables the fullfsync flag. This flag determines whether or not the F_FULLFSYNC
     * syncing method is used on systems that support it. The default value of the fullfsync flag is
     * off. Only Mac OS X supports F_FULLFSYNC.
     * 
     * @param enable True to enable; false to disable.
     * @see [www.sqlite.org/pragma.html.pragma_fullfsync](https://www.sqlite.org/pragma.html.pragma_fullfsync)
     */
    fun enableFullSync(enable: Boolean) {
        set(Pragma.FULL_SYNC, enable)
    }

    /**
     * Sets the incremental_vacuum value; the number of pages to be removed from the [freelist](https://www.sqlite.org/fileformat2.html#freelist). The database file is
     * truncated by the same amount.
     * 
     * @param numberOfPagesToBeRemoved The number of pages to be removed.
     * @see [www.sqlite.org/pragma.html.pragma_incremental_vacuum](https://www.sqlite.org/pragma.html.pragma_incremental_vacuum)
     */
    fun incrementalVacuum(numberOfPagesToBeRemoved: Int) {
        set(Pragma.INCREMENTAL_VACUUM, numberOfPagesToBeRemoved)
    }

    /**
     * Sets the journal mode for databases associated with the current database connection.
     * 
     * @param mode One of [JournalMode]
     * @see [www.sqlite.org/pragma.html.pragma_journal_mode](https://www.sqlite.org/pragma.html.pragma_journal_mode)
     */
    fun setJournalMode(mode: JournalMode) {
        setPragma(Pragma.JOURNAL_MODE, mode.name)
    }

    /**
     * Sets the journal_size_limit. This setting limits the size of the rollback-journal and WAL
     * files left in the file-system after transactions or checkpoints.
     * 
     * @param limit Limit value in bytes. A negative number implies no limit.
     * @see [www.sqlite.org/pragma.html.pragma_journal_size_limit](https://www.sqlite.org/pragma.html.pragma_journal_size_limit)
     */
    fun setJournalSizeLimit(limit: Int) {
        set(Pragma.JOURNAL_SIZE_LIMIT, limit)
    }

    /**
     * Sets the value of the legacy_file_format flag. When this flag is enabled, new SQLite
     * databases are created in a file format that is readable and writable by all versions of
     * SQLite going back to 3.0.0. When the flag is off, new databases are created using the latest
     * file format which might not be readable or writable by versions of SQLite prior to 3.3.0.
     * 
     * @param use True to turn on legacy file format; false to turn off.
     * @see [www.sqlite.org/pragma.html.pragma_legacy_file_format](https://www.sqlite.org/pragma.html.pragma_legacy_file_format)
     */
    fun useLegacyFileFormat(use: Boolean) {
        set(Pragma.LEGACY_FILE_FORMAT, use)
    }

    /**
     * Sets the value of the legacy_alter_table flag. When this flag is on, the ALTER TABLE RENAME
     * command (for changing the name of a table) works as it did in SQLite 3.24.0 (2018-06-04) and
     * earlier.When the flag is off, using the ALTER TABLE RENAME command will mean that all
     * references to the table anywhere in the schema will be converted to the new name.
     * 
     * @param flag True to turn on legacy alter table behaviour; false to turn off.
     * @see [](https://www.sqlite.org/pragma.html.pragma_legacy_alter_table</a>
    ) */
    fun setLegacyAlterTable(flag: Boolean) {
        set(Pragma.LEGACY_ALTER_TABLE, flag)
    }

    enum class LockingMode : PragmaValue {
        NORMAL,
        EXCLUSIVE;

        override val value
            get() = name
    }

    /**
     * Sets the database connection locking-mode.
     * 
     * @param mode One of [LockingMode]
     * @see [www.sqlite.org/pragma.html.pragma_locking_mode](https://www.sqlite.org/pragma.html.pragma_locking_mode)
     */
    fun setLockingMode(mode: LockingMode) {
        setPragma(Pragma.LOCKING_MODE, mode.name)
    }

    /**
     * Sets the page size of the database. The page size must be a power of two between 512 and
     * 65536 inclusive.
     * 
     * @param numBytes A power of two between 512 and 65536 inclusive.
     * @see [www.sqlite.org/pragma.html.pragma_page_size](https://www.sqlite.org/pragma.html.pragma_page_size)
     */
    fun setPageSize(numBytes: Int) {
        set(Pragma.PAGE_SIZE, numBytes)
    }

    /**
     * Sets the maximum number of pages in the database file.
     * 
     * @param numPages Number of pages.
     * @see [www.sqlite.org/pragma.html.pragma_max_page_count](https://www.sqlite.org/pragma.html.pragma_max_page_count)
     */
    fun setMaxPageCount(numPages: Int) {
        set(Pragma.MAX_PAGE_COUNT, numPages)
    }

    /**
     * Enables or disables useReadUncommittedIsolationMode.
     * 
     * @param useReadUncommittedIsolationMode True to turn on; false to disable. disabled otherwise.
     * @see [www.sqlite.org/pragma.html.pragma_read_uncommitted](https://www.sqlite.org/pragma.html.pragma_read_uncommitted)
     */
    fun setReadUncommitted(useReadUncommittedIsolationMode: Boolean) {
        set(Pragma.READ_UNCOMMITTED, useReadUncommittedIsolationMode)
    }

    /**
     * Enables or disables the recursive trigger capability.
     * 
     * @param enable True to enable the recursive trigger capability.
     * @see [www.sqlite.org/pragma.html.pragma_recursive_triggers](www.sqlite.org/pragma.html.pragma_recursive_triggers)
     */
    fun enableRecursiveTriggers(enable: Boolean) {
        set(Pragma.RECURSIVE_TRIGGERS, enable)
    }

    /**
     * Enables or disables the reverse_unordered_selects flag. This setting causes SELECT statements
     * without an ORDER BY clause to emit their results in the reverse order of what they normally
     * would. This can help debug applications that are making invalid assumptions about the result
     * order.
     * 
     * @param enable True to enable reverse_unordered_selects.
     * @see [www.sqlite.org/pragma.html.pragma_reverse_unordered_selects](https://www.sqlite.org/pragma.html.pragma_reverse_unordered_selects)
     */
    fun enableReverseUnorderedSelects(enable: Boolean) {
        set(Pragma.REVERSE_UNORDERED_SELECTS, enable)
    }

    /**
     * Enables or disables the short_column_names flag. This flag affects the way SQLite names
     * columns of data returned by SELECT statements.
     * 
     * @param enable True to enable short_column_names.
     * @see [www.sqlite.org/pragma.html.pragma_short_column_names](https://www.sqlite.org/pragma.html.pragma_short_column_names)
     */
    fun enableShortColumnNames(enable: Boolean) {
        set(Pragma.SHORT_COLUMN_NAMES, enable)
    }

    enum class SynchronousMode : PragmaValue {
        OFF,
        NORMAL,
        FULL;

        override val value get() = name
    }

    /**
     * Changes the setting of the "synchronous" flag.
     * 
     * @param mode One of [SynchronousMode]:
     * 
     *  * OFF - SQLite continues without syncing as soon as it has handed data off to the
     * operating system
     *  * NORMAL - the SQLite database engine will still sync at the most critical moments,
     * but less often than in FULL mode
     *  * FULL - the SQLite database engine will use the xSync method of the VFS to ensure
     * that all content is safely written to the disk surface prior to continuing. This
     * ensures that an operating system crash or power failure will not corrupt the
     * database.
     * 
     * 
     * @see [www.sqlite.org/pragma.html.pragma_synchronous](https://www.sqlite.org/pragma.html.pragma_synchronous)
     */
    fun setSynchronous(mode: SynchronousMode) {
        setPragma(Pragma.SYNCHRONOUS, mode.name)
    }

    enum class TempStore : PragmaValue {
        DEFAULT,
        FILE,
        MEMORY;

        override val value get() = name
    }

    /**
     * Changes the setting of the "hexkey" flag.
     * 
     * @param mode One of [HexKeyMode]:
     * 
     *  * NONE - SQLite uses a string based password
     *  * SSE - the SQLite database engine will use pragma hexkey = '' to set the password
     *  * SQLCIPHER - the SQLite database engine calls pragma key = "x''" to set the password
     * 
     */
    fun setHexKeyMode(mode: HexKeyMode) {
        setPragma(Pragma.HEXKEY_MODE, mode.name)
    }

    enum class HexKeyMode : PragmaValue {
        NONE,
        SSE,
        SQLCIPHER;

        override val value get() = name
    }

    /**
     * Changes the setting of the "temp_store" parameter.
     * 
     * @param storeType One of [TempStore]:
     * 
     *  * DEFAULT - the compile-time C preprocessor macro SQLITE_TEMP_STORE is used to
     * determine where temporary tables and indices are stored
     *  * FILE - temporary tables and indices are stored in a file.
     * 
     *  * MEMORY - temporary tables and indices are kept in as if they were pure in-memory
     * databases memory
     * @see [www.sqlite.org/pragma.html.pragma_temp_store](https://www.sqlite.org/pragma.html.pragma_temp_store)
     */
    fun setTempStore(storeType: TempStore) {
        setPragma(Pragma.TEMP_STORE, storeType.name)
    }

    /**
     * Changes the value of the sqlite3_temp_directory global variable, which many operating-system
     * interface backends use to determine where to store temporary tables and indices.
     * 
     * @param directoryName Directory name for storing temporary tables and indices.
     * @see [www.sqlite.org/pragma.html.pragma_temp_store_directory](https://www.sqlite.org/pragma.html.pragma_temp_store_directory)
     */
    fun setTempStoreDirectory(directoryName: String) {
        setPragma(Pragma.TEMP_STORE_DIRECTORY, String.format("'%s'", directoryName))
    }

    /**
     * Set the value of the user-version. The user-version is not used internally by SQLite. It may
     * be used by applications for any purpose. The value is stored in the database header at offset
     * 60.
     * 
     * @param version A big-endian 32-bit signed integer.
     * @see [www.sqlite.org/pragma.html.pragma_user_version](https://www.sqlite.org/pragma.html.pragma_user_version)
     */
    fun setUserVersion(version: Int) {
        set(Pragma.USER_VERSION, version)
    }

    /**
     * Set the value of the application-id. The application-id is not used internally by SQLite.
     * Applications that use SQLite as their application file-format should set the Application ID
     * integer to a unique integer so that utilities such as file(1) can determine the specific file
     * type. The value is stored in the database header at offset 68.
     * 
     * @param id A big-endian 32-bit unsigned integer.
     * @see [www.sqlite.org/pragma.html.pragma_application_id](https://www.sqlite.org/pragma.html.pragma_application_id)
     */
    fun setApplicationId(id: Int) {
        set(Pragma.APPLICATION_ID, id)
    }

    enum class TransactionMode : PragmaValue {
        DEFERRED,
        IMMEDIATE,
        EXCLUSIVE;

        override val value get() = name

        companion object {
            fun getMode(mode: String): TransactionMode {
                return valueOf(mode.uppercase(Locale.getDefault()))
            }
        }
    }

    /**
     * Sets the mode that will be used to start transactions.
     * 
     * @param transactionMode One of DEFERRED, IMMEDIATE or EXCLUSIVE.
     * @see [https://www.sqlite.org/lang_transaction.html](https://www.sqlite.org/lang_transaction.html)
     */
    fun setTransactionMode(transactionMode: String) {
        this.transactionMode = TransactionMode.Companion.getMode(transactionMode)
    }

    var transactionMode: TransactionMode
        /**
         * @return The transaction mode.
         */
        get() = this.defaultConnectionConfig.transactionMode
        /**
         * Sets the mode that will be used to start transactions.
         * 
         * @param transactionMode One of [TransactionMode].
         * @see [https://www.sqlite.org/lang_transaction.html](https://www.sqlite.org/lang_transaction.html)
         */
        set(transactionMode) {
            this.defaultConnectionConfig.transactionMode = transactionMode
        }

    enum class DatePrecision : PragmaValue {
        SECONDS,
        MILLISECONDS;

        override val value get() = name

        companion object {
            fun getPrecision(precision: String): DatePrecision {
                return valueOf(precision.uppercase(Locale.getDefault()))
            }
        }
    }

    /**
     * @param datePrecision One of SECONDS or MILLISECONDS
     */
    fun setDatePrecision(datePrecision: String) {
        this.defaultConnectionConfig.datePrecision = DatePrecision.getPrecision(datePrecision)
    }

    enum class DateClass : PragmaValue {
        INTEGER,
        TEXT,
        REAL;

        override val value get() = name

        companion object {
            fun getDateClass(dateClass: String): DateClass {
                return valueOf(dateClass.uppercase(Locale.getDefault()))
            }
        }
    }

    /**
     * @param dateClass One of INTEGER, TEXT or REAL
     */
    fun setDateClass(dateClass: String) {
        this.defaultConnectionConfig.dateClass = DateClass.getDateClass(dateClass)
    }

    /**
     * @param dateStringFormat Format of date string
     */
    fun setDateStringFormat(dateStringFormat: String) {
        this.defaultConnectionConfig.dateStringFormat = dateStringFormat
    }

    /**
     * @param milliseconds Connect to DB timeout in milliseconds
     */
    fun setBusyTimeout(milliseconds: Int) {
        setPragma(Pragma.BUSY_TIMEOUT, milliseconds.toString())
        busyTimeout = milliseconds
    }

    fun getBusyTimeout(): Int {
        return busyTimeout
    }

    var isGetGeneratedKeys: Boolean
        get() = this.defaultConnectionConfig.isGetGeneratedKeys
        set(generatedKeys) {
            this.defaultConnectionConfig.isGetGeneratedKeys = generatedKeys
        }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o !is SQLiteConfig) return false
        val that = o
        return this.openModeFlags == that.openModeFlags && busyTimeout == that.busyTimeout && this.isExplicitReadOnly == that.isExplicitReadOnly && pragmaTable == that.pragmaTable
                && defaultConnectionConfig == that.defaultConnectionConfig
    }

    override fun hashCode(): Int {
        return Objects.hash(
            pragmaTable, this.openModeFlags, busyTimeout, this.isExplicitReadOnly, defaultConnectionConfig
        )
    }

    companion object {
        /* Date storage class*/
        const val DEFAULT_DATE_STRING_FORMAT: String = "yyyy-MM-dd HH:mm:ss.SSS"

        /* Default limits used by SQLite: https://www.sqlite.org/limits.html */
        private const val DEFAULT_MAX_LENGTH = 1000000000
        private const val DEFAULT_MAX_COLUMN = 2000
        private const val DEFAULT_MAX_SQL_LENGTH = 1000000
        private const val DEFAULT_MAX_FUNCTION_ARG = 100
        private const val DEFAULT_MAX_ATTACHED = 10
        private const val DEFAULT_MAX_PAGE_COUNT = 1073741823

        val driverPropertyInfo: Array<DriverPropertyInfo>
            /**
             * @return Array of DriverPropertyInfo objects.
             */
            get() {
                val pragma = Pragma.entries.toTypedArray()
                val buffer = ArrayList<DriverPropertyInfo>()
                buffer.ensureCapacity(pragma.size)

//                var index = 0
                for (p in Pragma.entries) {
                    val di = DriverPropertyInfo(p.pragmaName, null)
                    di.choices = p.choices
                    di.description = p.description
                    di.required = false
                    buffer.add(di)
//                    result[index++] = di
                }

                return buffer.toTypedArray()
            }

        @JvmField
        val pragmaSet: MutableSet<String> = TreeSet<String>()

        init {
            for (pragma in Pragma.entries) {
                pragmaSet.add(pragma.pragmaName)
            }
        }
    }
}
