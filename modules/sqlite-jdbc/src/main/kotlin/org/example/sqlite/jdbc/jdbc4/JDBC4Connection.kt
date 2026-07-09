package org.example.sqlite.jdbc.jdbc4

import org.example.sqlite.jdbc.SQLiteArray
import org.example.sqlite.jdbc.SqliteBlob
import org.example.sqlite.jdbc.SqliteNClob
import org.example.sqlite.jdbc.core.DB
import org.example.sqlite.jdbc.jdbc3.JDBC3Connection
import java.sql.*
import java.sql.Array
import java.util.Properties

class JDBC4Connection(db: DB) : JDBC3Connection(db) {
    @Throws(SQLException::class)
    public override fun createStatement(rst: Int, rsc: Int, rsh: Int): Statement {
        checkOpen()
        checkCursor(rst, rsc, rsh)

        return JDBC4Statement(this)
    }

    @Throws(SQLException::class)
    public override fun prepareStatement(sql: String, rst: Int, rsc: Int, rsh: Int): PreparedStatement {
        checkOpen()
        checkCursor(rst, rsc, rsh)

        return JDBC4PreparedStatement(this, sql)
    }

    // JDBC 4
    /**
     * @see Connection.isClosed
     */
    @Throws(SQLException::class)
    override fun isClosed(): Boolean {
        return super.isClosed()
    }

    @Throws(ClassCastException::class)
    override fun <T> unwrap(iface: Class<T?>): T? {
        // caller should invoke isWrapperFor prior to unwrap
        return iface.cast(this)
    }

    override fun isWrapperFor(iface: Class<*>): Boolean {
        return iface.isInstance(this)
    }

    @Throws(SQLException::class)
    override fun createClob(): Clob? {
        return SqliteNClob()
    }

    @Throws(SQLException::class)
    override fun createBlob(): Blob? {
        return SqliteBlob()
    }

    @Throws(SQLException::class)
    override fun createNClob(): NClob? {
        return SqliteNClob()
    }

    @Throws(SQLException::class)
    override fun createSQLXML(): SQLXML? {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun isValid(timeout: Int): Boolean {
        return !isClosed && createStatement().use {
            it.execute("select 1")
        }
    }

    @Throws(SQLClientInfoException::class)
    override fun setClientInfo(name: String, value: String?) {
        // TODO Auto-generated method stub
    }

    @Throws(SQLClientInfoException::class)
    override fun setClientInfo(properties: Properties) {
        // TODO Auto-generated method stub
    }

    @Throws(SQLException::class)
    override fun getClientInfo(name: String): String? {
        // TODO Auto-generated method stub
        return null
    }

    @Throws(SQLException::class)
    override fun getClientInfo(): Properties? {
        // SQLite stores no client info; return an empty set per the JDBC contract.
        return Properties()
    }

    @Throws(SQLException::class)
    override fun createArrayOf(typeName: String, elements: kotlin.Array<Any?>): Array? {
        // carray 기반 — PreparedStatement.setArray + `IN (SELECT value FROM carray(?))` 에서 사용
        return SQLiteArray(typeName, elements)
    }
}
