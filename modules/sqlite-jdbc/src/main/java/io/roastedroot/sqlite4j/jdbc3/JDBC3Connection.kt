package io.roastedroot.sqlite4j.jdbc3

import io.roastedroot.sqlite4j.SQLiteConfig
import io.roastedroot.sqlite4j.SQLiteConnection
import io.roastedroot.sqlite4j.SQLiteOpenMode
import java.sql.*
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

abstract class JDBC3Connection protected constructor(url: String, fileName: String, prop: Properties) :
    SQLiteConnection(url, fileName, prop) {
    private val savePoint = AtomicInteger(0)
    private var typeMap: Map<String, Class<*>>? = null

    private var readOnly = false

    /**
     * This will try to enforce the transaction mode if SQLiteConfig#isExplicitReadOnly is true and
     * auto commit is disabled.
     * 
     * 
     *  * If this connection is read only, the PRAGMA query_only will be set
     *  * If this connection is not read only:
     * 
     *  * if no statement has been executed, PRAGMA query_only will be set to false, and an
     * IMMEDIATE transaction will be started
     *  * if a statement has already been executed, an exception is thrown
     * 
     * 
     * 
     * @throws SQLException if a statement has already been executed on this connection, then the
     * transaction cannot be upgraded to write
     */
    @Suppress("deprecation")
    @Throws(SQLException::class)
    fun tryEnforceTransactionMode() {
        // important note: read-only mode is only supported when auto-commit is disabled
        if (database.config.isExplicitReadOnly
            && !this.getAutoCommit() && this.currentTransactionMode != null
        ) {
            if (isReadOnly()) {
                // this is a read-only transaction, make sure all writing operations are rejected by
                // the DB
                // (note: this pragma is evaluated on a per-transaction basis by SQLite)
                database._exec("PRAGMA query_only = true;")
            } else {
                if (currentTransactionMode == SQLiteConfig.TransactionMode.DEFERRED) {
                    if (isFirstStatementExecuted) {
                        // first statement was already executed; cannot upgrade to write
                        // transaction!
                        throw SQLException(
                            "A statement has already been executed on this connection; cannot"
                                    + " upgrade to write transaction"
                        )
                    } else {
                        // this is the first statement in the transaction; close and create an
                        // immediate one
                        database._exec("commit; /* need to explicitly upgrade transaction */")

                        // start the write transaction
                        database._exec("PRAGMA query_only = false;")
                        database
                            ._exec("BEGIN IMMEDIATE; /* explicitly upgrade transaction */")
                        currentTransactionMode = SQLiteConfig.TransactionMode.IMMEDIATE
                    }
                }
            }
        }
    }

    /**
     * @see java.sql.Connection.getCatalog
     */
    @Throws(SQLException::class)
    override fun getCatalog(): String? {
        checkOpen()
        return null
    }

    /**
     * @see java.sql.Connection.setCatalog
     */
    @Throws(SQLException::class)
    override fun setCatalog(catalog: String?) {
        checkOpen()
    }

    /**
     * @see java.sql.Connection.getHoldability
     */
    @Throws(SQLException::class)
    override fun getHoldability(): Int {
        checkOpen()
        return ResultSet.CLOSE_CURSORS_AT_COMMIT
    }

    /**
     * @see java.sql.Connection.setHoldability
     */
    @Throws(SQLException::class)
    override fun setHoldability(h: Int) {
        checkOpen()
        if (h != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw SQLException("SQLite only supports CLOSE_CURSORS_AT_COMMIT")
        }
    }

    /**
     * @see java.sql.Connection.getTypeMap
     */
    @Throws(SQLException::class)
    override fun getTypeMap(): Map<String, Class<*>> {
        synchronized(this) {
            if (this.typeMap == null) {
                this.typeMap = HashMap<String, Class<*>>()
            }
            return this.typeMap!!.toMap()
        }
    }

    /**
     * @see java.sql.Connection.setTypeMap
     */
    @Throws(SQLException::class)
    override fun setTypeMap(map: Map<String, Class<*>>) {
        synchronized(this) {
            this.typeMap = map
        }
    }

    /**
     * @see java.sql.Connection.isReadOnly
     */
    override fun isReadOnly(): Boolean {
        val config = database.config
        return ( // the entire database is read-only
                ((config.openModeFlags and SQLiteOpenMode.READONLY.flag) != 0) // the flag was set explicitly by the user on this connection
                        || (config.isExplicitReadOnly && this.readOnly))
    }

    /**
     * @see java.sql.Connection.setReadOnly
     */
    @Throws(SQLException::class)
    override fun setReadOnly(ro: Boolean) {
        if (database.config.isExplicitReadOnly) {
            if (ro != readOnly && isFirstStatementExecuted) {
                throw SQLException(
                    "Cannot change Read-Only status of this connection: the first statement was"
                            + " already executed and the transaction is open."
                )
            }
        } else {
            // trying to change read-only flag
            if (ro != isReadOnly()) {
                throw SQLException(
                    "Cannot change read-only flag after establishing a connection. Use"
                            + " SQLiteConfig#setReadOnly and SQLiteConfig.createConnection()."
                )
            }
        }
        this.readOnly = ro
    }

    /**
     * @see java.sql.Connection.nativeSQL
     */
    override fun nativeSQL(sql: String): String {
        return sql
    }

    /**
     * @see java.sql.Connection.clearWarnings
     */
    @Throws(SQLException::class)
    override fun clearWarnings() {
    }

    /**
     * @see java.sql.Connection.getWarnings
     */
    @Throws(SQLException::class)
    override fun getWarnings(): SQLWarning? {
        return null
    }

    /**
     * @see java.sql.Connection.createStatement
     */
    @Throws(SQLException::class)
    override fun createStatement(): Statement {
        return createStatement(
            ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY,
            ResultSet.CLOSE_CURSORS_AT_COMMIT
        )
    }

    /**
     * @see java.sql.Connection.createStatement
     */
    @Throws(SQLException::class)
    override fun createStatement(rsType: Int, rsConcurr: Int): Statement {
        return createStatement(rsType, rsConcurr, ResultSet.CLOSE_CURSORS_AT_COMMIT)
    }

    /**
     * @see java.sql.Connection.createStatement
     */
    @Throws(SQLException::class)
    abstract override fun createStatement(rst: Int, rsc: Int, rsh: Int): Statement

    /**
     * @see java.sql.Connection.prepareCall
     */
    @Throws(SQLException::class)
    override fun prepareCall(sql: String): CallableStatement {
        return prepareCall(
            sql,
            ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY,
            ResultSet.CLOSE_CURSORS_AT_COMMIT
        )
    }

    /**
     * @see java.sql.Connection.prepareCall
     */
    @Throws(SQLException::class)
    override fun prepareCall(sql: String, rst: Int, rsc: Int): CallableStatement {
        return prepareCall(sql, rst, rsc, ResultSet.CLOSE_CURSORS_AT_COMMIT)
    }

    /**
     * @see java.sql.Connection.prepareCall
     */
    @Throws(SQLException::class)
    override fun prepareCall(sql: String, rst: Int, rsc: Int, rsh: Int): CallableStatement {
        throw SQLException("SQLite does not support Stored Procedures")
    }

    /**
     * @see java.sql.Connection.prepareStatement
     */
    @Throws(SQLException::class)
    override fun prepareStatement(sql: String): PreparedStatement {
        return prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
    }

    /**
     * @see java.sql.Connection.prepareStatement
     */
    @Throws(SQLException::class)
    override fun prepareStatement(sql: String, autoC: Int): PreparedStatement {
        return prepareStatement(sql)
    }

    /**
     * @see java.sql.Connection.prepareStatement
     */
    @Throws(SQLException::class)
    override fun prepareStatement(sql: String, colInds: IntArray): PreparedStatement {
        return prepareStatement(sql)
    }

    /**
     * @see java.sql.Connection.prepareStatement
     */
    @Throws(SQLException::class)
    override fun prepareStatement(sql: String, colNames: Array<String>): PreparedStatement {
        return prepareStatement(sql)
    }

    /**
     * @see java.sql.Connection.prepareStatement
     */
    @Throws(SQLException::class)
    override fun prepareStatement(sql: String, rst: Int, rsc: Int): PreparedStatement {
        return prepareStatement(sql, rst, rsc, ResultSet.CLOSE_CURSORS_AT_COMMIT)
    }

    /**
     * @see java.sql.Connection.prepareStatement
     */
    @Throws(SQLException::class)
    abstract override fun prepareStatement(sql: String, rst: Int, rsc: Int, rsh: Int): PreparedStatement

    /**
     * @see java.sql.Connection.setSavepoint
     */
    @Throws(SQLException::class)
    override fun setSavepoint(): Savepoint {
        checkOpen()
        if (getAutoCommit()) {
            // when a SAVEPOINT is the outermost savepoint and not
            // with a BEGIN...COMMIT then the behavior is the same
            // as BEGIN DEFERRED TRANSACTION
            // https://www.sqlite.org/lang_savepoint.html
            connectionConfig.isAutoCommit = false
        }
        val sp: Savepoint = JDBC3Savepoint(savePoint.incrementAndGet())
        database.exec(String.format("SAVEPOINT %s", sp.getSavepointName()), false)
        return sp
    }

    /**
     * @see java.sql.Connection.setSavepoint
     */
    @Throws(SQLException::class)
    override fun setSavepoint(name: String): Savepoint {
        checkOpen()
        if (getAutoCommit()) {
            // when a SAVEPOINT is the outermost savepoint and not
            // with a BEGIN...COMMIT then the behavior is the same
            // as BEGIN DEFERRED TRANSACTION
            // https://www.sqlite.org/lang_savepoint.html
            connectionConfig.isAutoCommit = false
        }
        val sp: Savepoint = JDBC3Savepoint(savePoint.incrementAndGet(), name)
        database.exec(String.format("SAVEPOINT %s", sp.getSavepointName()), false)
        return sp
    }

    /**
     * @see java.sql.Connection.releaseSavepoint
     */
    @Throws(SQLException::class)
    override fun releaseSavepoint(savepoint: Savepoint) {
        checkOpen()
        if (getAutoCommit()) {
            throw SQLException("database in auto-commit mode")
        }
        database
            .exec(String.format("RELEASE SAVEPOINT %s", savepoint.getSavepointName()), false)
    }

    /**
     * @see java.sql.Connection.rollback
     */
    @Throws(SQLException::class)
    override fun rollback(savepoint: Savepoint) {
        checkOpen()
        if (getAutoCommit()) {
            throw SQLException("database in auto-commit mode")
        }
        database
            .exec(
                String.format("ROLLBACK TO SAVEPOINT %s", savepoint.getSavepointName()),
                getAutoCommit()
            )
    }

    @Throws(SQLException::class)
    override fun createStruct(t: String, attr: Array<Any>): Struct? {
        throw SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver")
    }
}
