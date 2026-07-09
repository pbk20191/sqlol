package org.example.sqlite.jdbc.jdbc4

import org.example.sqlite.jdbc.core.Codes
import org.example.sqlite.jdbc.core.CoreStatement
import org.example.sqlite.jdbc.jdbc3.JDBC3ResultSet
import java.io.*
import java.math.BigDecimal
import java.net.MalformedURLException
import java.net.URL
import javax.sql.rowset.serial.SerialBlob
import org.example.sqlite.jdbc.SqliteNClob
import java.sql.*
import java.sql.Array
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.min

class JDBC4ResultSet(stmt: CoreStatement) : JDBC3ResultSet(stmt), ResultSet, ResultSetMetaData {
    @Throws(SQLException::class)
    override fun close() {
        val wasOpen = isOpen() // prevent close() recursion
        super.close()
        // close-on-completion regardless of closeStmt
        if (wasOpen && stmt is JDBC4Statement) {
            val stat = stmt
            // check if its not closed already in which case no-op
            if (stat.closeOnCompletion && !stat.isClosed()) {
                stat.close()
            }
        }
    }

    // JDBC 4
    @Throws(ClassCastException::class)
    override fun <T> unwrap(iface: Class<T?>): T? {
        return iface.cast(this)
    }

    override fun isWrapperFor(iface: Class<*>): Boolean {
        return iface.isInstance(this)
    }

    @Throws(SQLException::class)
    override fun getRowId(columnIndex: Int): RowId? {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun getRowId(columnLabel: String): RowId? {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateRowId(columnIndex: Int, x: RowId) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateRowId(columnLabel: String, x: RowId) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun getHoldability(): Int {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT
    }

    @Throws(SQLException::class)
    override fun isClosed(): Boolean {
        return !isOpen()
    }

    @Throws(SQLException::class)
    override fun updateNString(columnIndex: Int, nString: String) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateNString(columnLabel: String, nString: String) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateNClob(columnIndex: Int, nClob: NClob) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateNClob(columnLabel: String, nClob: NClob) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun getNClob(columnIndex: Int): NClob? {
        // SQLite 에 national-character 구분 없음 — Clob 와 동일
        val s = getString(columnIndex)
        return if (s == null) null else SqliteNClob(s)
    }

    @Throws(SQLException::class)
    override fun getNClob(columnLabel: String): NClob? {
        return getNClob(findColumn(columnLabel))
    }

    @Throws(SQLException::class)
    override fun getSQLXML(columnIndex: Int): SQLXML? {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun getSQLXML(columnLabel: String): SQLXML? {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateSQLXML(columnIndex: Int, xmlObject: SQLXML) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateSQLXML(columnLabel: String, xmlObject: SQLXML) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun getNString(columnIndex: Int): String? {
        // SQLite has no national-character distinction; NString == String.
        return getString(columnIndex)
    }

    @Throws(SQLException::class)
    override fun getNString(columnLabel: String): String? {
        return getString(columnLabel)
    }

    @Throws(SQLException::class)
    override fun getNCharacterStream(col: Int): Reader? {
        val data = getString(col)
        return getNCharacterStreamInternal(data)
    }

    private fun getNCharacterStreamInternal(data: String?): Reader? {
        if (data == null) {
            return null
        }
        val reader: Reader = StringReader(data)
        return reader
    }

    @Throws(SQLException::class)
    override fun getNCharacterStream(col: String): Reader? {
        val data = getString(col)
        return getNCharacterStreamInternal(data)
    }

    @Throws(SQLException::class)
    override fun updateNCharacterStream(columnIndex: Int, x: Reader, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateNCharacterStream(columnLabel: String, reader: Reader, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateAsciiStream(columnIndex: Int, x: InputStream, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateBinaryStream(columnIndex: Int, x: InputStream, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateCharacterStream(columnIndex: Int, x: Reader, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateAsciiStream(columnLabel: String, x: InputStream, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateBinaryStream(columnLabel: String, x: InputStream, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateCharacterStream(columnLabel: String, reader: Reader, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateBlob(columnIndex: Int, inputStream: InputStream, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateBlob(columnLabel: String, inputStream: InputStream, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateClob(columnIndex: Int, reader: Reader, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateClob(columnLabel: String, reader: Reader, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateNClob(columnIndex: Int, reader: Reader, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateNClob(columnLabel: String, reader: Reader, length: Long) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateNCharacterStream(columnIndex: Int, x: Reader) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateNCharacterStream(columnLabel: String, reader: Reader) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateAsciiStream(columnIndex: Int, x: InputStream) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateBinaryStream(columnIndex: Int, x: InputStream) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateCharacterStream(columnIndex: Int, x: Reader) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateAsciiStream(columnLabel: String, x: InputStream) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateBinaryStream(columnLabel: String, x: InputStream) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateCharacterStream(columnLabel: String, reader: Reader) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateBlob(columnIndex: Int, inputStream: InputStream) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateBlob(columnLabel: String, inputStream: InputStream) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateClob(columnIndex: Int, reader: Reader) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateClob(columnLabel: String, reader: Reader) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateNClob(columnIndex: Int, reader: Reader) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun updateNClob(columnLabel: String, reader: Reader) {
        // TODO Support this
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun <T> getObject(columnIndex: Int, type: Class<T?>): T? {
        if (type == null) throw SQLException("requested type cannot be null")
        if (type == String::class.java) return type.cast(getString(columnIndex))
        if (type == Boolean::class.javaObjectType) return type.cast(getBoolean(columnIndex))
        if (type == BigDecimal::class.java) return type.cast(getBigDecimal(columnIndex))
        if (type == ByteArray::class.java) return type.cast(getBytes(columnIndex))
        if (type == Date::class.java) return type.cast(getDate(columnIndex))
        if (type == Time::class.java) return type.cast(getTime(columnIndex))
        if (type == Timestamp::class.java) return type.cast(getTimestamp(columnIndex))
        if (type == LocalDate::class.java) {
            try {
                val date = getDate(columnIndex)
                if (date != null) return type.cast(date.toLocalDate())
                else return null
            } catch (sqlException: SQLException) {
                // If the FastDateParser failed, try parse it with LocalDate.
                // It's a workaround for a value like '2022-12-1' (i.e no time presents).
                return type.cast(LocalDate.parse(getString(columnIndex)))
            }
        }
        if (type == LocalTime::class.java) {
            try {
                val time = getTime(columnIndex)
                if (time != null) return type.cast(time.toLocalTime())
                else return null
            } catch (sqlException: SQLException) {
                // If the FastDateParser failed, try parse it with LocalTime.
                // It's a workaround for a value like '11:22:22' (i.e no date presents).
                return type.cast(LocalTime.parse(getString(columnIndex)))
            }
        }
        if (type == LocalDateTime::class.java) {
            try {
                val timestamp = getTimestamp(columnIndex)
                if (timestamp != null) return type.cast(timestamp.toLocalDateTime())
                else return null
            } catch (e: SQLException) {
                // If the FastDateParser failed, try parse it with LocalDateTime.
                return type.cast(LocalDateTime.parse(getString(columnIndex)))
            }
        }

        val columnType = safeGetColumnType(markCol(columnIndex))
        if (type == Double::class.javaObjectType) {
            if (columnType == Codes.SQLITE_INTEGER || columnType == Codes.SQLITE_FLOAT) return type.cast(
                getDouble(
                    columnIndex
                )
            )
            throw SQLException("Bad value for type Double")
        }
        if (type == Long::class.javaObjectType) {
            if (columnType == Codes.SQLITE_INTEGER || columnType == Codes.SQLITE_FLOAT) return type.cast(
                getLong(
                    columnIndex
                )
            )
            throw SQLException("Bad value for type Long")
        }
        if (type == Float::class.javaObjectType) {
            if (columnType == Codes.SQLITE_INTEGER || columnType == Codes.SQLITE_FLOAT) return type.cast(
                getFloat(
                    columnIndex
                )
            )
            throw SQLException("Bad value for type Float")
        }
        if (type == Int::class.javaObjectType) {
            if (columnType == Codes.SQLITE_INTEGER || columnType == Codes.SQLITE_FLOAT) return type.cast(
                getInt(
                    columnIndex
                )
            )
            throw SQLException("Bad value for type Integer")
        }

        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun <T> getObject(columnLabel: String, type: Class<T?>): T? {
        return getObject<T?>(findColumn(columnLabel), type)
    }

    protected fun unsupported(): SQLException {
        return SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver")
    }

    // ResultSet ////////////////////////////////////////////////////
    @Throws(SQLException::class)
    override fun getArray(i: Int): Array? {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun getArray(col: String): Array? {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun getAsciiStream(col: Int): InputStream? {
        val data = getString(col)
        return getAsciiStreamInternal(data)
    }

    @Throws(SQLException::class)
    override fun getAsciiStream(col: String): InputStream? {
        val data = getString(col)
        return getAsciiStreamInternal(data)
    }

    private fun getAsciiStreamInternal(data: String?): InputStream? {
        if (data == null) {
            return null
        }
        val inputStream: InputStream?
        try {
            inputStream = ByteArrayInputStream(data.toByteArray(charset("ASCII")))
        } catch (e: UnsupportedEncodingException) {
            return null
        }
        return inputStream
    }

    @Deprecated("")
    @Throws(SQLException::class)
    override fun getBigDecimal(col: Int, s: Int): BigDecimal? {
        // Deprecated scale parameter is ignored, as in most JDBC drivers.
        return getBigDecimal(col)
    }

    @Deprecated("")
    @Throws(SQLException::class)
    override fun getBigDecimal(col: String, s: Int): BigDecimal? {
        return getBigDecimal(col)
    }

    @Throws(SQLException::class)
    override fun getBlob(col: Int): Blob? {
        val bytes = getBytes(col) ?: return null
        return SerialBlob(bytes)
    }

    @Throws(SQLException::class)
    override fun getBlob(col: String): Blob? {
        return getBlob(findColumn(col))
    }

    @Throws(SQLException::class)
    override fun getClob(col: Int): Clob? {
        val clob = getString(col)
        return if (clob == null) null else SqliteNClob(clob)
    }

    @Throws(SQLException::class)
    override fun getClob(col: String): Clob? {
        val clob = getString(col)
        return if (clob == null) null else SqliteNClob(clob)
    }

    @Throws(SQLException::class)
    override fun getObject(col: Int, map: Map<String, Class<*>>): Any? {
        // SQLite has no user-defined types; the type map is ignored.
        return getObject(col)
    }

    @Throws(SQLException::class)
    override fun getObject(col: String, map: Map<String, Class<*>>): Any? {
        return getObject(col)
    }

    @Throws(SQLException::class)
    override fun getRef(i: Int): Ref? {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun getRef(col: String): Ref? {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun getUnicodeStream(col: Int): InputStream? {
        return getAsciiStream(col)
    }

    @Throws(SQLException::class)
    override fun getUnicodeStream(col: String): InputStream? {
        return getAsciiStream(col)
    }

    @Throws(SQLException::class)
    override fun getURL(col: Int): URL? {
        val s = getString(col) ?: return null
        try {
            return URL(s)
        } catch (e: MalformedURLException) {
            throw SQLException("Invalid URL: " + s, e)
        }
    }

    @Throws(SQLException::class)
    override fun getURL(col: String): URL? {
        return getURL(findColumn(col))
    }

    @Throws(SQLException::class)
    override fun insertRow() {
        throw SQLException("ResultSet is TYPE_FORWARD_ONLY")
    }

    @Throws(SQLException::class)
    override fun moveToCurrentRow() {
        throw SQLException("ResultSet is TYPE_FORWARD_ONLY")
    }

    @Throws(SQLException::class)
    override fun moveToInsertRow() {
        throw SQLException("ResultSet is TYPE_FORWARD_ONLY")
    }

    @Throws(SQLException::class)
    override fun last(): Boolean {
        throw SQLException("ResultSet is TYPE_FORWARD_ONLY")
    }

    @Throws(SQLException::class)
    override fun previous(): Boolean {
        throw SQLException("ResultSet is TYPE_FORWARD_ONLY")
    }

    @Throws(SQLException::class)
    override fun relative(rows: Int): Boolean {
        throw SQLException("ResultSet is TYPE_FORWARD_ONLY")
    }

    @Throws(SQLException::class)
    override fun absolute(row: Int): Boolean {
        throw SQLException("ResultSet is TYPE_FORWARD_ONLY")
    }

    @Throws(SQLException::class)
    override fun afterLast() {
        throw SQLException("ResultSet is TYPE_FORWARD_ONLY")
    }

    @Throws(SQLException::class)
    override fun beforeFirst() {
        throw SQLException("ResultSet is TYPE_FORWARD_ONLY")
    }

    @Throws(SQLException::class)
    override fun first(): Boolean {
        throw SQLException("ResultSet is TYPE_FORWARD_ONLY")
    }

    @Throws(SQLException::class)
    override fun cancelRowUpdates() {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun deleteRow() {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateArray(col: Int, x: Array) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateArray(col: String, x: Array) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateAsciiStream(col: Int, x: InputStream, l: Int) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateAsciiStream(col: String, x: InputStream, l: Int) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateBigDecimal(col: Int, x: BigDecimal) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateBigDecimal(col: String, x: BigDecimal) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateBinaryStream(c: Int, x: InputStream, l: Int) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateBinaryStream(c: String, x: InputStream, l: Int) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateBlob(col: Int, x: Blob) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateBlob(col: String, x: Blob) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateBoolean(col: Int, x: Boolean) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateBoolean(col: String, x: Boolean) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateByte(col: Int, x: Byte) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateByte(col: String, x: Byte) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateBytes(col: Int, x: ByteArray) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateBytes(col: String, x: ByteArray) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateCharacterStream(c: Int, x: Reader, l: Int) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateCharacterStream(c: String, r: Reader, l: Int) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateClob(col: Int, x: Clob) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateClob(col: String, x: Clob) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateDate(col: Int, x: Date) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateDate(col: String, x: Date) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateDouble(col: Int, x: Double) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateDouble(col: String, x: Double) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateFloat(col: Int, x: Float) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateFloat(col: String, x: Float) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateInt(col: Int, x: Int) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateInt(col: String, x: Int) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateLong(col: Int, x: Long) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateLong(col: String, x: Long) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateNull(col: Int) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateNull(col: String) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateObject(c: Int, x: Any) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateObject(c: Int, x: Any, s: Int) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateObject(col: String, x: Any) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateObject(c: String, x: Any, s: Int) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateRef(col: Int, x: Ref) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateRef(c: String, x: Ref) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateRow() {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateShort(c: Int, x: Short) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateShort(c: String, x: Short) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateString(c: Int, x: String) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateString(c: String, x: String) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateTime(c: Int, x: Time) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateTime(c: String, x: Time) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateTimestamp(c: Int, x: Timestamp) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun updateTimestamp(c: String, x: Timestamp) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    override fun refreshRow() {
        throw unsupported()
    }
}
