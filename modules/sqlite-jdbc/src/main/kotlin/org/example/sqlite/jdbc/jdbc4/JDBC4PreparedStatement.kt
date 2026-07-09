package org.example.sqlite.jdbc.jdbc4

import org.example.sqlite.jdbc.SQLiteConnection
import org.example.sqlite.jdbc.jdbc3.JDBC3PreparedStatement
import java.io.IOException
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
        // SQLite has no national-character distinction; NString == String.
        setString(parameterIndex, value)
    }

    @Throws(SQLException::class)
    override fun setNCharacterStream(parameterIndex: Int, value: Reader?, length: Long) {
        setCharacterStream(parameterIndex, value, length)
    }

    @Throws(SQLException::class)
    override fun setNClob(parameterIndex: Int, value: NClob?) {
        // SQLite 에 national-character 구분 없음 — Clob 와 동일
        setClob(parameterIndex, value)
    }

    @Throws(SQLException::class)
    override fun setClob(parameterIndex: Int, reader: Reader?, length: Long) {
        setCharacterStream(parameterIndex, reader, length)
    }

    @Throws(SQLException::class)
    override fun setBlob(parameterIndex: Int, inputStream: InputStream?, length: Long) {
        setBinaryStream(parameterIndex, inputStream, length)
    }

    @Throws(SQLException::class)
    override fun setNClob(parameterIndex: Int, reader: Reader?, length: Long) {
        setCharacterStream(parameterIndex, reader, length)
    }

    @Throws(SQLException::class)
    override fun setSQLXML(parameterIndex: Int, xmlObject: SQLXML?) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun setAsciiStream(parameterIndex: Int, x: InputStream?, length: Long) {
        setAsciiStream(parameterIndex, x, length.toInt())
    }

    @Throws(SQLException::class)
    override fun setBinaryStream(parameterIndex: Int, x: InputStream?, length: Long) {
        setBinaryStream(parameterIndex, x, length.toInt())
    }

    @Throws(SQLException::class)
    override fun setCharacterStream(parameterIndex: Int, reader: Reader?, length: Long) {
        if (reader == null) {
            setString(parameterIndex, null)
        } else {
            setCharacterStream(parameterIndex, reader, length.toInt())
        }
    }

    @Throws(SQLException::class)
    override fun setAsciiStream(parameterIndex: Int, x: InputStream?) {
        if (x == null) {
            setString(parameterIndex, null)
            return
        }
        try {
            setString(parameterIndex, String(x.readBytes(), Charsets.US_ASCII))
        } catch (e: IOException) {
            throw SQLException(e)
        }
    }

    @Throws(SQLException::class)
    override fun setBinaryStream(parameterIndex: Int, x: InputStream?) {
        if (x == null) {
            setBytes(parameterIndex, null)
            return
        }
        try {
            setBytes(parameterIndex, x.readBytes())
        } catch (e: IOException) {
            throw SQLException(e)
        }
    }

    @Throws(SQLException::class)
    override fun setCharacterStream(parameterIndex: Int, reader: Reader?) {
        if (reader == null) {
            setString(parameterIndex, null)
            return
        }
        try {
            setString(parameterIndex, reader.readText())
        } catch (e: IOException) {
            throw SQLException(e)
        }
    }

    @Throws(SQLException::class)
    override fun setNCharacterStream(parameterIndex: Int, value: Reader?) {
        setCharacterStream(parameterIndex, value)
    }

    @Throws(SQLException::class)
    override fun setClob(parameterIndex: Int, reader: Reader?) {
        setCharacterStream(parameterIndex, reader)
    }

    @Throws(SQLException::class)
    override fun setBlob(parameterIndex: Int, inputStream: InputStream?) {
        setBinaryStream(parameterIndex, inputStream)
    }

    @Throws(SQLException::class)
    override fun setNClob(parameterIndex: Int, reader: Reader?) {
        setCharacterStream(parameterIndex, reader)
    }
}
