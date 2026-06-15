/*--------------------------------------------------------------------------
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
package org.example.sqlite.jdbc.javax

import org.example.sqlite.jdbc.SQLiteConnection
import org.example.sqlite.jdbc.core.DB
import org.example.sqlite.jdbc.jdbc4.JDBC4PooledConnection
import org.example.sqlite.jdbc.jdbc4.JDBC4PreparedStatement
import org.example.sqlite.jdbc.jdbc4.JDBC4Statement
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.*
import java.sql.Array
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.ConnectionEvent
import javax.sql.ConnectionEventListener
import kotlin.concurrent.Volatile

class SQLitePooledConnection internal constructor(physicalConn: SQLiteConnection) : JDBC4PooledConnection() {
    var physicalConn: SQLiteConnection?
        protected set

    @Volatile
    protected var handleConn: Connection? = null

    var listeners: MutableList<ConnectionEventListener> = ArrayList<ConnectionEventListener>()
        protected set

    /**
     * Constructor.
     * 
     * @param physicalConn The physical Connection.
     */
    init {
        this.physicalConn = physicalConn
    }

    /**
     * @see javax.sql.PooledConnection.close
     */
    @Throws(SQLException::class)
    override fun close() {
        if (handleConn != null) {
            listeners.clear()
            handleConn!!.close()
        }

        if (physicalConn != null) {
            try {
                physicalConn!!.close()
            } finally {
                physicalConn = null
            }
        }
    }

    /**
     * @see javax.sql.PooledConnection.getConnection
     */
    @Throws(SQLException::class)
    override fun getConnection(): Connection {
        if (handleConn != null) handleConn!!.close()

        handleConn =
            Proxy.newProxyInstance(
                javaClass.getClassLoader(),
                arrayOf<Class<*>>(Connection::class.java),
                object : InvocationHandler {
                    var isClosed: Boolean = false

                    @Throws(Throwable::class)
                    override fun invoke(proxy: Any, method: Method, args: kotlin.Array<out Any?>?): Any? {
                        try {
                            val name = method.name
                            if ("close" == name) {
                                val event =
                                    ConnectionEvent(
                                        this@SQLitePooledConnection
                                    )

                                for (i in listeners.indices.reversed()) {
                                    listeners[i].connectionClosed(event)
                                }

                                if (!physicalConn!!.getAutoCommit()) {
                                    physicalConn!!.rollback()
                                }
                                physicalConn!!.setAutoCommit(true)
                                isClosed = true

                                return null // don't close physical connection
                            } else if ("isClosed" == name) {
                                if (!isClosed) isClosed =
                                    (method.invoke(
                                        physicalConn,
                                        *(args ?: emptyArray())
                                    ) as Boolean)

                                return isClosed
                            }

                            if (isClosed) {
                                throw SQLException("Connection is closed")
                            }

                            return method.invoke(physicalConn, *(args ?: emptyArray()))
                        } catch (e: SQLException) {
                            if ("database connection closed"
                                == e.message
                            ) {
                                val event =
                                    ConnectionEvent(
                                        this@SQLitePooledConnection, e
                                    )

                                var i = listeners.size - 1
                                while (i >= 0) {
                                    listeners[i].connectionErrorOccurred(event)
                                    i--
                                }
                            }

                            throw e
                        } catch (ex: InvocationTargetException) {
                            throw ex.cause!!
                        }
                    }
                }) as Connection

        return handleConn!!
    }

    /**
     * @see javax.sql.PooledConnection.addConnectionEventListener
     */
    override fun addConnectionEventListener(listener: ConnectionEventListener) {
        listeners.add(listener)
    }

    /**
     * @see      javax.sql.PooledConnection.removeConnectionEventListener
     */
    override fun removeConnectionEventListener(listener: ConnectionEventListener) {
        listeners.remove(listener)
    }
}

internal class SQLitePooledConnectionHandle(private val parent: SQLitePooledConnection) :
    SQLiteConnection(parent.physicalConn!!.database) {
    private val isClosed = AtomicBoolean(false)

    @Throws(SQLException::class)
    override fun createStatement(): Statement {
        return JDBC4Statement(this)
    }

    @Throws(SQLException::class)
    override fun prepareStatement(sql: String): PreparedStatement {
        return JDBC4PreparedStatement(this, sql)
    }

    @Throws(SQLException::class)
    override fun prepareCall(sql: String): CallableStatement? {
        return null
    }

    @Throws(SQLException::class)
    override fun nativeSQL(sql: String): String? {
        return null
    }

    @Throws(SQLException::class)
    override fun setAutoCommit(autoCommit: Boolean) {
    }

    @Throws(SQLException::class)
    override fun getAutoCommit(): Boolean {
        return false
    }

    @Throws(SQLException::class)
    override fun commit() {
    }

    @Throws(SQLException::class)
    override fun rollback() {
    }

    @Throws(SQLException::class)
    override fun close() {
        val event = ConnectionEvent(parent)

        val listeners = parent.listeners
        for (i in listeners.indices.reversed()) {
            listeners.get(i).connectionClosed(event)
        }

        if (!parent.physicalConn!!.getAutoCommit()) {
            parent.physicalConn!!.rollback()
        }
        parent.physicalConn!!.setAutoCommit(true)
        isClosed.set(true)
    }

    override fun isClosed(): Boolean {
        return isClosed.get()
    }

    @Throws(SQLException::class)
    override fun getMetaData(): DatabaseMetaData {
        return parent.physicalConn!!.metaData
    }

    @Throws(SQLException::class)
    override fun setReadOnly(readOnly: Boolean) {
    }

    @Throws(SQLException::class)
    override fun isReadOnly(): Boolean {
        return false
    }

    @Throws(SQLException::class)
    override fun setCatalog(catalog: String) {
    }

    @Throws(SQLException::class)
    override fun getCatalog(): String? {
        return null
    }

    @Throws(SQLException::class)
    override fun setTransactionIsolation(level: Int) {
    }

    override fun getTransactionIsolation(): Int {
        return 0
    }

    @Throws(SQLException::class)
    override fun getWarnings(): SQLWarning? {
        return null
    }

    @Throws(SQLException::class)
    override fun clearWarnings() {
    }

    @Throws(SQLException::class)
    override fun createStatement(resultSetType: Int, resultSetConcurrency: Int): Statement? {
        return null
    }

    @Throws(SQLException::class)
    override fun prepareStatement(
        sql: String, resultSetType: Int, resultSetConcurrency: Int
    ): PreparedStatement? {
        return null
    }

    @Throws(SQLException::class)
    override fun prepareCall(sql: String, resultSetType: Int, resultSetConcurrency: Int): CallableStatement? {
        return null
    }

    @Throws(SQLException::class)
    override fun getTypeMap(): MutableMap<String, Class<*>>? {
        return null
    }

    @Throws(SQLException::class)
    override fun setTypeMap(map: MutableMap<String, Class<*>>) {
    }

    @Throws(SQLException::class)
    override fun setHoldability(holdability: Int) {
    }

    @Throws(SQLException::class)
    override fun getHoldability(): Int {
        return 0
    }

    @Throws(SQLException::class)
    override fun setSavepoint(): Savepoint? {
        return null
    }

    @Throws(SQLException::class)
    override fun setSavepoint(name: String): Savepoint? {
        return null
    }

    @Throws(SQLException::class)
    override fun rollback(savepoint: Savepoint) {
    }

    @Throws(SQLException::class)
    override fun releaseSavepoint(savepoint: Savepoint) {
    }

    @Throws(SQLException::class)
    override fun createStatement(
        resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int
    ): Statement? {
        return null
    }

    @Throws(SQLException::class)
    override fun prepareStatement(
        sql: String, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int
    ): PreparedStatement? {
        return null
    }

    @Throws(SQLException::class)
    override fun prepareCall(
        sql: String, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int
    ): CallableStatement? {
        return null
    }

    @Throws(SQLException::class)
    override fun prepareStatement(sql: String, autoGeneratedKeys: Int): PreparedStatement? {
        return null
    }

    @Throws(SQLException::class)
    override fun prepareStatement(sql: String, columnIndexes: IntArray): PreparedStatement? {
        return null
    }

    @Throws(SQLException::class)
    override fun prepareStatement(sql: String, vararg columnNames: String): PreparedStatement? {
//        super.prepareStatement()
        return null
    }

    @Throws(SQLException::class)
    override fun createClob(): Clob? {
        return null
    }

    @Throws(SQLException::class)
    override fun createBlob(): Blob? {
        return null
    }

    @Throws(SQLException::class)
    override fun createNClob(): NClob? {
        return null
    }

    @Throws(SQLException::class)
    override fun createSQLXML(): SQLXML? {
        return null
    }

    @Throws(SQLException::class)
    override fun isValid(timeout: Int): Boolean {
        return false
    }

    @Throws(SQLClientInfoException::class)
    override fun setClientInfo(name: String, value: String) {
    }

    @Throws(SQLClientInfoException::class)
    override fun setClientInfo(properties: Properties) {
    }

    @Throws(SQLException::class)
    override fun getClientInfo(name: String): String? {
        return null
    }

    @Throws(SQLException::class)
    override fun getClientInfo(): Properties? {
        return null
    }

    @Throws(SQLException::class)
    override fun createArrayOf(typeName: String, elements: kotlin.Array<Any>): Array? {
        return null
    }

    @Throws(SQLException::class)
    override fun createStruct(typeName: String, attributes: kotlin.Array<Any>): Struct? {
        return null
    }

    @Throws(SQLException::class)
    override fun setSchema(schema: String) {
    }

    @Throws(SQLException::class)
    override fun getSchema(): String? {
        return null
    }

    @Throws(SQLException::class)
    override fun abort(executor: Executor) {
    }

    @Throws(SQLException::class)
    override fun setNetworkTimeout(executor: Executor, milliseconds: Int) {
    }

    @Throws(SQLException::class)
    override fun getNetworkTimeout(): Int {
        return 0
    }

    @Throws(SQLException::class)
    override fun <T> unwrap(iface: Class<T?>): T? {
        return null
    }

    @Throws(SQLException::class)
    override fun isWrapperFor(iface: Class<*>): Boolean {
        return false
    }

    override var busyTimeout: Int
        get() = 0
        set(timeoutMillis) {}

    override val database: DB
        get() = parent.physicalConn!!.database
}
