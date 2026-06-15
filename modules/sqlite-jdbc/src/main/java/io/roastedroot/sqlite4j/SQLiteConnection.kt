package io.roastedroot.sqlite4j

import io.roastedroot.sqlite4j.SQLiteConfig.TransactionMode
import io.roastedroot.sqlite4j.core.CoreDatabaseMetaData
import io.roastedroot.sqlite4j.core.DB
import io.roastedroot.sqlite4j.core.WorkerDB
import io.roastedroot.sqlite4j.core.WorkerDBFactory
import io.roastedroot.sqlite4j.jdbc4.JDBC4DatabaseMetaData
import java.io.File
import java.io.IOException
import java.net.MalformedURLException
import java.net.URISyntaxException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executor

abstract class SQLiteConnection : Connection {
    private val dbHandle: DB
    open val database: DB
        get() = dbHandle
    private var meta: CoreDatabaseMetaData? = null
    val connectionConfig: SQLiteConnectionConfig

    var currentTransactionMode: TransactionMode? = null
    var isFirstStatementExecuted: Boolean = false

    /**
     * Connection constructor for reusing an existing DB handle
     *
     * @param db
     */
    constructor(db: DB) {
        this.dbHandle = db
        connectionConfig = db.config.newConnectionConfig()
    }

    /**
     * Constructor to create a connection to a database at the given location.
     */
    constructor(url: String, fileName: String) : this(url, fileName, Properties())

    /**
     * Constructor to create a pre-configured connection to a database at the given location.
     */
    constructor(url: String, fileName: String, prop: Properties) {
        val newDB = open(url, fileName, prop)
        this.dbHandle = newDB
        try {
            val config = this.database.config
            this.connectionConfig = this.database.config.newConnectionConfig()
            config.apply(this)
            this.currentTransactionMode = this.database.config.transactionMode
            // connection starts in "clean" state (even though some PRAGMA statements were executed)
            this.isFirstStatementExecuted = false
        } catch (t: Throwable) {
            try {
                newDB.close()
            } catch (e: Exception) {
                t.addSuppressed(e)
            }
            throw t
        }
    }

    @Throws(SQLException::class)
    fun getSQLiteDatabaseMetaData(): CoreDatabaseMetaData {
        checkOpen()

        if (meta == null) {
            meta = JDBC4DatabaseMetaData(this)
        }

        return meta!!
    }

    @Throws(SQLException::class)
    override fun getMetaData(): DatabaseMetaData {
        return getSQLiteDatabaseMetaData() as DatabaseMetaData
    }

    val url: String
        get() = database.url

    @Throws(SQLException::class)
    override fun setSchema(schema: String) {
        // TODO
    }

    @Throws(SQLException::class)
    override fun getSchema(): String? {
        // TODO
        return null
    }

    @Throws(SQLException::class)
    override fun abort(executor: Executor) {
        // TODO
    }

    @Throws(SQLException::class)
    override fun setNetworkTimeout(executor: Executor, milliseconds: Int) {
        // TODO
    }

    @Throws(SQLException::class)
    override fun getNetworkTimeout(): Int {
        // TODO
        return 0
    }

    /**
     * Checks whether the type, concurrency, and holdability settings for a [ResultSet] are
     * supported by the SQLite interface.
     */
    @Throws(SQLException::class)
    protected fun checkCursor(rst: Int, rsc: Int, rsh: Int) {
        if (rst != ResultSet.TYPE_FORWARD_ONLY)
            throw SQLException("SQLite only supports TYPE_FORWARD_ONLY cursors")
        if (rsc != ResultSet.CONCUR_READ_ONLY)
            throw SQLException("SQLite only supports CONCUR_READ_ONLY cursors")
        if (rsh != ResultSet.CLOSE_CURSORS_AT_COMMIT)
            throw SQLException("SQLite only supports closing cursors at commit")
    }

    /**
     * Sets the mode that will be used to start transactions on this connection.
     */
    protected fun setTransactionMode(mode: TransactionMode) {
        connectionConfig.transactionMode = mode
    }

    /**
     * @see java.sql.Connection.getTransactionIsolation
     */
    override fun getTransactionIsolation(): Int {
        return connectionConfig.transactionIsolation
    }

    /**
     * @see java.sql.Connection.setTransactionIsolation
     */
    @Throws(SQLException::class)
    override fun setTransactionIsolation(level: Int) {
        checkOpen()

        when (level) {
            Connection.TRANSACTION_READ_COMMITTED,
            Connection.TRANSACTION_REPEATABLE_READ,
            Connection.TRANSACTION_SERIALIZABLE ->
                database.exec("PRAGMA read_uncommitted = false;", autoCommit)
            Connection.TRANSACTION_READ_UNCOMMITTED ->
                database.exec("PRAGMA read_uncommitted = true;", autoCommit)
            else ->
                throw SQLException(
                    "Unsupported transaction isolation level: " +
                        level +
                        ". Must be one of TRANSACTION_READ_UNCOMMITTED," +
                        " TRANSACTION_READ_COMMITTED, TRANSACTION_REPEATABLE_READ, or" +
                        " TRANSACTION_SERIALIZABLE in java.sql.Connection"
                )
        }
        connectionConfig.transactionIsolation = level
    }

    /**
     * @see java.sql.Connection.getAutoCommit
     */
    @Throws(SQLException::class)
    override fun getAutoCommit(): Boolean {
        checkOpen()
        return connectionConfig.isAutoCommit
    }

    /**
     * @see java.sql.Connection.setAutoCommit
     */
    @Throws(SQLException::class)
    override fun setAutoCommit(ac: Boolean) {
        checkOpen()
        if (connectionConfig.isAutoCommit == ac) return

        connectionConfig.isAutoCommit = ac

        if (this.connectionConfig.isAutoCommit) {
            database.exec("commit;", ac)
            this.currentTransactionMode = null
        } else {
            database.exec(this.transactionPrefix()!!,ac)
            this.currentTransactionMode = this.connectionConfig.transactionMode
        }
    }

    /**
     * @return The busy timeout value for the connection.
     */
    open var busyTimeout: Int
        get() = database.config.getBusyTimeout()
        @Throws(SQLException::class)
        set(timeoutMillis) {
            database.config.setBusyTimeout(timeoutMillis)
            database.busy_timeout(timeoutMillis)
        }

    @Throws(SQLException::class)
    fun setLimit(limit: SQLiteLimits, value: Int) {
        // Calling sqlite3_limit with a negative number is a no-op:
        // https://www.sqlite.org/c3ref/limit.html
        if (value >= 0) {
            database.limit(limit.id, value)
        }
    }

    @Throws(SQLException::class)
    fun getLimit(limit: SQLiteLimits) {
        database.limit(limit.id, -1)
    }

    @Throws(SQLException::class)
    override fun isClosed(): Boolean {
        return database.isClosed()
    }

    /**
     * @see java.sql.Connection.close
     */
    @Throws(SQLException::class)
    override fun close() {
        if (isClosed()) return
        if (meta != null) meta!!.close()

        cache.close(database as WorkerDB)
    }

    /**
     * Whether an SQLite library interface to the database has been established.
     */
    @Throws(SQLException::class)
    protected fun checkOpen() {
        if (isClosed()) throw SQLException("database connection closed")
    }

    /**
     * @return Compile-time library version numbers.
     */
    @Throws(SQLException::class)
    fun libversion(): String {
        checkOpen()
        return database.libversion()!!
    }

    /**
     * @see java.sql.Connection.commit
     */
    @Throws(SQLException::class)
    override fun commit() {
        checkOpen()
        if (connectionConfig.isAutoCommit) throw SQLException("database in auto-commit mode")
        database.exec("commit;", autoCommit)
        database.exec(this.transactionPrefix()!!,autoCommit)
        this.isFirstStatementExecuted = false
        this.currentTransactionMode = this.connectionConfig.transactionMode
    }

    /**
     * @see java.sql.Connection.rollback
     */
    @Throws(SQLException::class)
    override fun rollback() {
        checkOpen()
        if (connectionConfig.isAutoCommit) throw SQLException("database in auto-commit mode")
        database.exec("rollback;", autoCommit)
        database.exec(this.transactionPrefix()!!,autoCommit)
        this.isFirstStatementExecuted = false
        this.currentTransactionMode = this.connectionConfig.transactionMode
    }

    /**
     * Add a listener for DB update events, see https://www.sqlite.org/c3ref/update_hook.html
     */
    fun addUpdateListener(listener: SQLiteUpdateListener) {
        database.addUpdateListener(listener)
    }

    /**
     * Remove a listener registered for DB update events.
     */
    fun removeUpdateListener(listener: SQLiteUpdateListener) {
        database.removeUpdateListener(listener)
    }

    /**
     * Add a listener for DB commit/rollback events, see
     * https://www.sqlite.org/c3ref/commit_hook.html
     */
    fun addCommitListener(listener: SQLiteCommitListener) {
        database.addCommitListener(listener)
    }

    /**
     * Remove a listener registered for DB commit/rollback events.
     */
    fun removeCommitListener(listener: SQLiteCommitListener) {
        database.removeCommitListener(listener)
    }

    protected fun transactionPrefix(): String? {
        return this.connectionConfig.transactionPrefix()
    }

    /**
     * Returns a byte array representing the schema content.
     */
    @Throws(SQLException::class)
    fun serialize(schema: String): ByteArray {
        return database.serialize(schema)!!
    }

    /**
     * Deserialize the schema using the given byte array.
     */
    @Throws(SQLException::class)
    fun deserialize(schema: String, buff: ByteArray) {
        database.deserialize(schema, buff)
    }

    companion object {
        private const val RESOURCE_NAME_PREFIX = ":resource:"

        private val cache = WorkerDBFactory()

        /**
         * Opens a connection to the database using an SQLite library.
         */
        @Throws(SQLException::class)
        private fun open(url: String, origFileName: String, props: Properties): DB {
            // Create a copy of the given properties
            val newProps = Properties()
            newProps.putAll(props)

            // Extract pragma as properties
            var fileName = extractPragmasFromFilename(url, origFileName, newProps)
            val config = SQLiteConfig(newProps)

            // check the path to the file exists
            if (!fileName.isEmpty() &&
                ":memory:" != fileName &&
                !fileName.startsWith("file:") &&
                !fileName.contains("mode=memory")
            ) {
                if (fileName.startsWith(RESOURCE_NAME_PREFIX)) {
                    val resourceName = fileName.substring(RESOURCE_NAME_PREFIX.length)

                    // search the class path
                    val contextCL = Thread.currentThread().contextClassLoader
                    var resourceAddr = contextCL.getResource(resourceName)
                    if (resourceAddr == null) {
                        try {
                            resourceAddr = URL(resourceName)
                        } catch (e: MalformedURLException) {
                            throw SQLException(
                                String.format("resource %s not found: %s", resourceName, e)
                            )
                        }
                    }

                    try {
                        fileName = extractResource(resourceAddr).absolutePath
                    } catch (e: IOException) {
                        throw SQLException(
                            String.format("failed to load %s: %s", resourceName, e)
                        )
                    }
                } else {
                    fileName = File(fileName).absoluteFile.absolutePath
                }
            }

            val isMemory =
                fileName.isEmpty() ||
                    ":memory:" == fileName ||
                    fileName.contains("mode=memory")

            // load the native DB
            val db: DB
            try {
                db = cache.create(url, fileName, config, isMemory)
            } catch (e: Exception) {
                val err = SQLException("Error opening connection")
                err.initCause(e)
                throw err
            }
            db.open(fileName, config.openModeFlags)
            return db
        }

        /**
         * Returns a file name from the given resource address.
         */
        @Throws(IOException::class)
        private fun extractResource(resourceAddr: URL): File {
            if (resourceAddr.protocol == "file") {
                try {
                    return File(resourceAddr.toURI())
                } catch (e: URISyntaxException) {
                    throw IOException(e.message)
                }
            }

            val tempFolder = File(System.getProperty("java.io.tmpdir")).absolutePath
            val dbFileName = String.format("sqlite-jdbc-tmp-%s.db", UUID.randomUUID())
            val dbFile = File(tempFolder, dbFileName)

            if (dbFile.exists()) {
                val resourceLastModified = resourceAddr.openConnection().lastModified
                val tmpFileLastModified = dbFile.lastModified()
                if (resourceLastModified < tmpFileLastModified) {
                    return dbFile
                } else {
                    // remove the old DB file
                    val deletionSucceeded = dbFile.delete()
                    if (!deletionSucceeded) {
                        throw IOException(
                            "failed to remove existing DB file: " + dbFile.absolutePath
                        )
                    }
                }
            }

            val conn = resourceAddr.openConnection()
            // Disable caches to avoid keeping unnecessary file references after the single-use copy
            conn.useCaches = false
            conn.getInputStream().use { reader ->
                Files.copy(reader, dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return dbFile
            }
        }

        /**
         * Extracts PRAGMA values from the filename and sets them into the Properties object which
         * will be used to build the SQLConfig. The sanitized filename is returned.
         */
        @Throws(SQLException::class)
        @JvmStatic
        protected fun extractPragmasFromFilename(
            url: String,
            filename: String,
            prop: Properties
        ): String {
            val parameterDelimiter = filename.indexOf('?')
            if (parameterDelimiter == -1) {
                // nothing to extract
                return filename
            }

            val sb = StringBuilder()
            sb.append(filename.substring(0, parameterDelimiter))

            var nonPragmaCount = 0
            val parameters = filename.substring(parameterDelimiter + 1).split("&".toRegex())
                .dropLastWhile { it.isEmpty() }.toTypedArray()
            for (i in parameters.indices) {
                // process parameters in reverse-order, last specified pragma value wins
                val parameter = parameters[parameters.size - 1 - i].trim()

                if (parameter.isEmpty()) {
                    // duplicated &&& sequence, drop
                    continue
                }

                val kvp = parameter.split("=".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val key = kvp[0].trim().lowercase()
                if (SQLiteConfig.pragmaSet.contains(key)) {
                    if (kvp.size == 1) {
                        throw SQLException(
                            String.format(
                                "Please specify a value for PRAGMA %s in URL %s", key, url
                            )
                        )
                    }
                    val value = kvp[1].trim()
                    if (!value.isEmpty()) {
                        if (prop.containsKey(key)) {
                            // IGNORE: this allows DriverManager.getConnection(String, Properties)
                            // to override URL parameters programmatically.
                        } else {
                            prop.setProperty(key, value)
                        }
                    }
                } else {
                    // not a Pragma, retain as part of filename
                    sb.append(if (nonPragmaCount == 0) '?' else '&')
                    sb.append(parameter)
                    nonPragmaCount++
                }
            }

            return sb.toString()
        }
    }
}
