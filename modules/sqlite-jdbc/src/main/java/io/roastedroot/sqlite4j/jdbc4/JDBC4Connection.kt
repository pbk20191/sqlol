package io.roastedroot.sqlite4j.jdbc4

import io.roastedroot.sqlite4j.jdbc3.JDBC3Connection
import java.sql.*
import java.sql.Array
import java.util.*

class JDBC4Connection(url: String, fileName: String, prop: Properties) : JDBC3Connection(url, fileName, prop) {
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
     * @see java.sql.Connection.isClosed
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
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun createBlob(): Blob? {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun createNClob(): NClob? {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
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
        // TODO Auto-generated method stub
        return null
    }
}
