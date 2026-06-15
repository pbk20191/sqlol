/*--------------------------------------------------------------------------
 *  Copyright 2010 Taro L. Saito
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *--------------------------------------------------------------------------*/
// --------------------------------------
// sqlite-jdbc Project
//
// SQLiteDataSource.java
// Since: Mar 11, 2010
//
// $URL$
// $Author$
// --------------------------------------
package org.example.sqlite.jdbc

import org.example.sqlite.jdbc.SQLiteConfig.*
import org.example.sqlite.jdbc.jdbc4.JDBC4Connection
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.Properties
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Provides [DataSource] API for configuring SQLite database connection
 * 
 * @author leo
 */
open class SQLiteDataSource : DataSource {
    /**
     * @return The configuration for the data source.
     */
    /**
     * Sets a data source's configuration.
     * 
     * @param config The configuration.
     */
    @JvmField
    var config: SQLiteConfig

    @Transient
    private var logger: PrintWriter? = null
    private var loginTimeout = 1

    /**
     * @return The location of the database file.
     */
    /**
     * Sets the location of the database file.
     * 
     * @param url The location of the database file.
     */
    @JvmField
    var url: String = JDBC.PREFIX // use memory database in default
    /**
     * @return The name of the database if one was set.
     * @see SQLiteDataSource.setDatabaseName
     */
    /**
     * Sets the database name.
     * 
     * @param databaseName The name of the database
     */
    var databaseName: String = "" // the name of the current database

    /** Default constructor.  */
    constructor() {
        this.config = SQLiteConfig() // default configuration
    }

    /**
     * Creates a data source based on the provided configuration.
     * 
     * @param config The configuration for the data source.
     */
    constructor(config: SQLiteConfig) {
        this.config = config
    }

    /**
     * Enables or disables the sharing of the database cache and schema data structures between
     * connections to the same database.
     * 
     * @param enable True to enable; false to disable.
     * @see [https://www.sqlite.org/c3ref/enable_shared_cache.html](https://www.sqlite.org/c3ref/enable_shared_cache.html)
     */
    fun setSharedCache(enable: Boolean) {
        config.setSharedCache(enable)
    }

    /**
     * Enables or disables extension loading.
     * 
     * @param enable True to enable; false to disable.
     * @see [https://www.sqlite.org/c3ref/load_extension.html](https://www.sqlite.org/c3ref/load_extension.html)
     */
    fun setLoadExtension(enable: Boolean) {
        config.enableLoadExtension(enable)
    }

    /**
     * Sets the database to be opened in read-only mode
     * 
     * @param readOnly True to enable; false to disable.
     * @see [https://www.sqlite.org/c3ref/c_open_autoproxy.html](https://www.sqlite.org/c3ref/c_open_autoproxy.html)
     */
    fun setReadOnly(readOnly: Boolean) {
        config.setReadOnly(readOnly)
    }

    /**
     * Sets the amount of time that the connection's busy handler will wait when a table is locked.
     * 
     * @param milliseconds The number of milliseconds to wait.
     * @see [https://www.sqlite.org/pragma.html.pragma_busy_timeout](https://www.sqlite.org/pragma.html.pragma_busy_timeout)
     */
    fun setBusyTimeout(milliseconds: Int) {
        config.setBusyTimeout(milliseconds)
    }

    /**
     * Sets the suggested maximum number of database disk pages that SQLite will hold in memory at
     * once per open database file.
     * 
     * @param numberOfPages The number of database disk pages.
     * @see [https://www.sqlite.org/pragma.html.pragma_cache_size](https://www.sqlite.org/pragma.html.pragma_cache_size)
     */
    fun setCacheSize(numberOfPages: Int) {
        config.setCacheSize(numberOfPages)
    }

    /**
     * Enables or disables case sensitivity for the built-in LIKE operator.
     * 
     * @param enable True to enable; false to disable.
     * @see [https://www.sqlite.org/compile.html.case_sensitive_like](https://www.sqlite.org/compile.html.case_sensitive_like)
     */
    fun setCaseSensitiveLike(enable: Boolean) {
        config.enableCaseSensitiveLike(enable)
    }

    /**
     * Enables or disables the count-changes flag. When enabled INSERT, UPDATE and DELETE statements
     * return the number of rows they modified.
     * 
     * @param enable True to enable; false to disable.
     * @see [https://www.sqlite.org/pragma.html.pragma_count_changes](https://www.sqlite.org/pragma.html.pragma_count_changes)
     */
    fun setCountChanges(enable: Boolean) {
        config.enableCountChanges(enable)
    }

    /**
     * Sets the default maximum number of database disk pages that SQLite will hold in memory at
     * once per open database file.
     * 
     * @param numberOfPages The default suggested cache size.
     * @see [https://www.sqlite.org/pragma.html.pragma_cache_size](https://www.sqlite.org/pragma.html.pragma_cache_size)
     */
    fun setDefaultCacheSize(numberOfPages: Int) {
        config.setDefaultCacheSize(numberOfPages)
    }

    /**
     * Sets the text encoding used by the main database.
     * 
     * @param encoding One of "UTF-8", "UTF-16le" (little-endian UTF-16) or "UTF-16be" (big-endian
     * UTF-16).
     * @see [
     * https://www.sqlite.org/pragma.html.pragma_encoding](https://www.sqlite.org/pragma.html.pragma_encoding)
     */
    fun setEncoding(encoding: String) {
        config.setEncoding(SQLiteConfig.Encoding.getEncoding(encoding))
    }

    /**
     * Enables or disables the enforcement of foreign key constraints.
     * 
     * @param enforce True to enable; false to disable.
     * @see [
     * https://www.sqlite.org/pragma.html.pragma_foreign_keys](https://www.sqlite.org/pragma.html.pragma_foreign_keys)
     */
    fun setEnforceForeignKeys(enforce: Boolean) {
        config.enforceForeignKeys(enforce)
    }

    /**
     * Enables or disables the full_column_names flag. This flag together with the
     * short_column_names flag determine the way SQLite assigns names to result columns of SELECT
     * statements.
     * 
     * @param enable True to enable; false to disable.
     * @see [https://www.sqlite.org/pragma.html.pragma_full_column_names](https://www.sqlite.org/pragma.html.pragma_full_column_names)
     */
    fun setFullColumnNames(enable: Boolean) {
        config.enableFullColumnNames(enable)
    }

    /**
     * Enables or disables the fullfsync flag. This flag determines whether or not the F_FULLFSYNC
     * syncing method is used on systems that support it.
     * 
     * @param enable True to enable; false to disable.
     * @see [https://www.sqlite.org/pragma.html.pragma_fullfsync](https://www.sqlite.org/pragma.html.pragma_fullfsync)
     */
    fun setFullSync(enable: Boolean) {
        config.enableFullSync(enable)
    }

    /**
     * Set the incremental_vacuum value that causes up to N pages to be removed from the [https://www.sqlite.org/fileformat2.html#freelist](https://www.sqlite.org/fileformat2.html#freelist).
     * 
     * @param numberOfPagesToBeRemoved
     * @see [
     * https://www.sqlite.org/pragma.html.pragma_incremental_vacuum](https://www.sqlite.org/pragma.html.pragma_incremental_vacuum)
     */
    fun setIncrementalVacuum(numberOfPagesToBeRemoved: Int) {
        config.incrementalVacuum(numberOfPagesToBeRemoved)
    }

    /**
     * Sets the journal mode for databases associated with the current database connection.
     * 
     * @param mode One of DELETE, TRUNCATE, PERSIST, MEMORY, WAL or OFF.
     * @see [
     * https://www.sqlite.org/pragma.html.pragma_journal_mode](https://www.sqlite.org/pragma.html.pragma_journal_mode)
     */
    fun setJournalMode(mode: String) {
        config.setJournalMode(JournalMode.valueOf(mode))
    }

    /**
     * Sets the limit of the size of rollback-journal and WAL files left in the file-system after
     * transactions or checkpoints.
     * 
     * @param limit The default journal size limit is -1 (no limit).
     * @see [
     * https://www.sqlite.org/pragma.html.pragma_journal_size_limit](https://www.sqlite.org/pragma.html.pragma_journal_size_limit)
     */
    fun setJournalSizeLimit(limit: Int) {
        config.setJournalSizeLimit(limit)
    }

    /**
     * Set the value of the legacy_file_format flag. When this flag is on, new databases are created
     * in a file format that is readable and writable by all versions of SQLite going back to 3.0.0.
     * When the flag is off, new databases are created using the latest file format which might not
     * be readable or writable by versions of SQLite prior to 3.3.0.
     * 
     * @param use True to turn on; false to turn off.
     * @see [https://www.sqlite.org/pragma.html.pragma_legacy_file_format](https://www.sqlite.org/pragma.html.pragma_legacy_file_format)
     */
    fun setLegacyFileFormat(use: Boolean) {
        config.useLegacyFileFormat(use)
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
        config.setLegacyAlterTable(flag)
    }

    /**
     * Sets the database connection locking-mode.
     * 
     * @param mode Either NORMAL or EXCLUSIVE.
     * @see [
     * https://www.sqlite.org/pragma.html.pragma_locking_mode](https://www.sqlite.org/pragma.html.pragma_locking_mode)
     */
    fun setLockingMode(mode: String) {
        config.setLockingMode(SQLiteConfig.LockingMode.valueOf(mode))
    }

    /**
     * Set the page size of the database.
     * 
     * @param numBytes The page size must be a power of two between 512 and 65536 inclusive.
     * @see [
     * https://www.sqlite.org/pragma.html.pragma_page_size](https://www.sqlite.org/pragma.html.pragma_page_size)
     */
    fun setPageSize(numBytes: Int) {
        config.setPageSize(numBytes)
    }

    /**
     * Set the maximum number of pages in the database file.
     * 
     * @param numPages The maximum page count cannot be reduced below the current database size.
     * @see [
     * https://www.sqlite.org/pragma.html.pragma_max_page_count](https://www.sqlite.org/pragma.html.pragma_max_page_count)
     */
    fun setMaxPageCount(numPages: Int) {
        config.setMaxPageCount(numPages)
    }

    /**
     * Set READ UNCOMMITTED isolation
     * 
     * @param useReadUncommittedIsolationMode True to turn on; false to turn off.
     * @see [https://www.sqlite.org/pragma.html.pragma_read_uncommitted](https://www.sqlite.org/pragma.html.pragma_read_uncommitted)
     */
    fun setReadUncommitted(useReadUncommittedIsolationMode: Boolean) {
        config.setReadUncommitted(useReadUncommittedIsolationMode)
    }

    /**
     * Enables or disables the recursive trigger capability. Changing the recursive_triggers setting
     * affects the execution of all statements prepared using the database connection, including
     * those prepared before the setting was changed.
     * 
     * @param enable True to enable; false to disable.
     * @see [https://www.sqlite.org/pragma.html.pragma_recursive_triggers](https://www.sqlite.org/pragma.html.pragma_recursive_triggers)
     */
    fun setRecursiveTriggers(enable: Boolean) {
        config.enableRecursiveTriggers(enable)
    }

    /**
     * Enables or disables the reverse_unordered_selects flag. When enabled it causes SELECT
     * statements without an ORDER BY clause to emit their results in the reverse order of what they
     * normally would.
     * 
     * @param enable True to enable; false to disable.
     * @see [https://www.sqlite.org/pragma.html.pragma_reverse_unordered_selects](https://www.sqlite.org/pragma.html.pragma_reverse_unordered_selects)
     */
    fun setReverseUnorderedSelects(enable: Boolean) {
        config.enableReverseUnorderedSelects(enable)
    }

    /**
     * Enables or disables the short_column_names flag. This flag affects the way SQLite names
     * columns of data returned by SELECT statements.
     * 
     * @param enable True to enable; false to disable.
     * @see [https://www.sqlite.org/pragma.html.pragma_short_column_names](https://www.sqlite.org/pragma.html.pragma_short_column_names)
     * 
     * @see [https://www.sqlite.org/pragma.html.pragma_fullfsync](https://www.sqlite.org/pragma.html.pragma_fullfsync)
     */
    fun setShortColumnNames(enable: Boolean) {
        config.enableShortColumnNames(enable)
    }

    /**
     * Sets the setting of the "synchronous" flag.
     * 
     * @param mode One of OFF, NORMAL or FULL;
     * @see [
     * https://www.sqlite.org/pragma.html.pragma_synchronous](https://www.sqlite.org/pragma.html.pragma_synchronous)
     */
    fun setSynchronous(mode: String) {
        config.setSynchronous(SynchronousMode.valueOf(mode))
    }

    /**
     * Set the temp_store type which is used to determine where temporary tables and indices are
     * stored.
     * 
     * @param storeType One of "DEFAULT", "FILE", "MEMORY"
     * @see [https://www.sqlite.org/pragma.html.pragma_temp_store](https://www.sqlite.org/pragma.html.pragma_temp_store)
     */
    fun setTempStore(storeType: String) {
        config.setTempStore(TempStore.valueOf(storeType))
    }

    /**
     * Set the value of the sqlite3_temp_directory global variable, which many operating-system
     * interface backends use to determine where to store temporary tables and indices.
     * 
     * @param directoryName The temporary directory name.
     * @see [https://www.sqlite.org/pragma.html.pragma_temp_store_directory](https://www.sqlite.org/pragma.html.pragma_temp_store_directory)
     */
    fun setTempStoreDirectory(directoryName: String) {
        config.setTempStoreDirectory(directoryName)
    }

    /**
     * Sets the mode that will be used to start transactions for this database.
     * 
     * @param transactionMode One of DEFERRED, IMMEDIATE or EXCLUSIVE.
     * @see [https://www.sqlite.org/lang_transaction.html](https://www.sqlite.org/lang_transaction.html)
     */
    fun setTransactionMode(transactionMode: String) {
        config.setTransactionMode(transactionMode)
    }

    /**
     * Configure where generated keys will be retrieved for this database.
     * 
     * @param generatedKeys true to retrieve generated keys
     */
    fun setGetGeneratedKeys(generatedKeys: Boolean) {
        config.isGetGeneratedKeys = generatedKeys
    }

    /**
     * Sets the value of the user-version. It is a big-endian 32-bit signed integer stored in the
     * database header at offset 60.
     * 
     * @param version
     * @see [https://www.sqlite.org/pragma.html.pragma_schema_version](https://www.sqlite.org/pragma.html.pragma_schema_version)
     */
    fun setUserVersion(version: Int) {
        config.setUserVersion(version)
    }

    // codes for the DataSource interface
    /**
     * @see DataSource.getConnection
     */
    @Throws(SQLException::class)
    override fun getConnection(): Connection? {
        return getConnection(null, null)
    }

    /**
     * @see DataSource.getConnection
     */
    @Throws(SQLException::class)
    override fun getConnection(username: String?, password: String?): SQLiteConnection? {
        val p = config.toProperties()
        if (username != null) p.put("user", username)
        if (password != null) p.put("pass", password)
        return createConnection(url, p)
    }

    /**
     * @see DataSource.getLogWriter
     */
    @Throws(SQLException::class)
    override fun getLogWriter(): PrintWriter? {
        return logger
    }

    /**
     * @see DataSource.getLoginTimeout
     */
    @Throws(SQLException::class)
    override fun getLoginTimeout(): Int {
        return loginTimeout
    }

    @Throws(SQLFeatureNotSupportedException::class)
    override fun getParentLogger(): Logger? {
        throw SQLFeatureNotSupportedException("getParentLogger")
    }

    /**
     * @see DataSource.setLogWriter
     */
    @Throws(SQLException::class)
    override fun setLogWriter(out: PrintWriter?) {
        this.logger = out
    }

    /**
     * @see DataSource.setLoginTimeout
     */
    @Throws(SQLException::class)
    override fun setLoginTimeout(seconds: Int) {
        loginTimeout = seconds
    }

    /**
     * Determines if this object wraps a given class.
     * 
     * @param iface The class to check.
     * @return True if it is an instance of the current class; false otherwise.
     * @throws SQLException
     */
    @Throws(SQLException::class)
    override fun isWrapperFor(iface: Class<*>): Boolean {
        return iface.isInstance(this)
    }

    /**
     * Casts this object to the given class.
     * 
     * @param iface The class to cast to.
     * @return The casted class.
     * @throws SQLException
     */
    @Throws(SQLException::class)
    override fun <T> unwrap(iface: Class<T?>): T? {
        return this as T
    }

    companion object {
        /**
         * Creates a new database connection to a given URL. This is the single connection-creation
         * entry point; both [JDBC.connect] and [getConnection] delegate here.
         *
         * @param url the URL
         * @param prop the properties
         * @return a Connection to the URL, or null if the URL is not an SQLite URL
         * @throws SQLException
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun createConnection(url: String, prop: Properties?): SQLiteConnection? {
            if (!JDBC.isValidURL(url)) return null
            val trimmed = url.trim { it <= ' ' }
            return JDBC4Connection(trimmed, JDBC.extractAddress(trimmed), prop ?: Properties())
        }
    }
}
