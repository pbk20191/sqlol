package io.roastedroot.sqlite4j.jdbc4

import io.roastedroot.sqlite4j.SQLiteConnection
import io.roastedroot.sqlite4j.jdbc3.JDBC3PreparedStatement
import java.io.InputStream
import java.io.Reader
import java.sql.*

class JDBC4PreparedStatement(conn: SQLiteConnection, sql: String) : JDBC3PreparedStatement(conn, sql),
    PreparedStatement, ParameterMetaData {
    override fun toString(): String {
        return sql + " \n parameters=" + batch.contentToString()
    }

    @Throws(SQLException::class)
    override fun executeLargeUpdate(): Long = super<JDBC3PreparedStatement>.executeLargeUpdate()

    // JDBC 4
    @Throws(SQLException::class)
    override fun setRowId(parameterIndex: Int, x: RowId?) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setNString(parameterIndex: Int, value: String?) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setNCharacterStream(parameterIndex: Int, value: Reader?, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setNClob(parameterIndex: Int, value: NClob?) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setClob(parameterIndex: Int, reader: Reader?, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setBlob(parameterIndex: Int, inputStream: InputStream?, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setNClob(parameterIndex: Int, reader: Reader?, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setSQLXML(parameterIndex: Int, xmlObject: SQLXML?) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setAsciiStream(parameterIndex: Int, x: InputStream?, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setBinaryStream(parameterIndex: Int, x: InputStream?, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setCharacterStream(parameterIndex: Int, reader: Reader?, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setAsciiStream(parameterIndex: Int, x: InputStream?) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setBinaryStream(parameterIndex: Int, x: InputStream?) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setCharacterStream(parameterIndex: Int, reader: Reader?) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setNCharacterStream(parameterIndex: Int, value: Reader?) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setClob(parameterIndex: Int, reader: Reader?) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setBlob(parameterIndex: Int, inputStream: InputStream?) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setNClob(parameterIndex: Int, reader: Reader?) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }
}
