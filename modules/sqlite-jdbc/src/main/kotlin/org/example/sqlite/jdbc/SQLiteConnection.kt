package org.example.sqlite.jdbc

import org.example.sqlite.jdbc.SQLiteConfig.TransactionMode
import org.example.sqlite.jdbc.core.CoreDatabaseMetaData
import org.example.sqlite.jdbc.core.DB
import org.example.sqlite.jdbc.core.WorkerDB
import org.example.sqlite.jdbc.core.WorkerDBFactory
import org.example.sqlite.jdbc.jdbc4.JDBC4DatabaseMetaData
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.Executor

abstract class SQLiteConnection : Connection {
    private val dbHandle: DB
    open val database: DB
        get() = dbHandle
    private var meta: CoreDatabaseMetaData? = null
    val connectionConfig: SQLiteConnectionConfig

    var currentTransactionMode: TransactionMode? = null
    var isFirstStatementExecuted: Boolean = false

    constructor(db: DB) {
        this.dbHandle = db
        connectionConfig = db.config.newConnectionConfig()
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
    fun getLimit(limit: SQLiteLimits): Int {
        // sqlite3_limit 에 음수를 주면 변경 없이 현재 값을 반환한다
        return database.limit(limit.id, -1)
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

        WorkerDBFactory.close(database as WorkerDB)
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
        return database.libversion()
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

}
