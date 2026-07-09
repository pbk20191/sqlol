package org.example.sqlite.jdbc.jdbc3

import org.example.sqlite.jdbc.SQLiteArray
import org.example.sqlite.jdbc.SQLiteConnection
import org.example.sqlite.jdbc.core.CorePreparedStatement
import org.example.sqlite.jdbc.core.DB
import org.example.sqlite.jdbc.core.SafeStmtPtr.SafePtrConsumer
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.io.UnsupportedEncodingException
import java.math.BigDecimal
import java.net.URL
import java.sql.*
import java.sql.Array
import java.util.*
import java.util.Date

abstract class JDBC3PreparedStatement protected constructor(conn: SQLiteConnection, sql: String) :
    CorePreparedStatement(conn, sql) {
    /**
     * @see java.sql.PreparedStatement.clearParameters
     */
    @Throws(SQLException::class)
    fun clearParameters() {
        checkOpen()
        pointer!!.safeRunConsume { obj, stmt -> obj.clear_bindings(stmt) }
        if (batch != null) for (i in batchPos..<batchPos + paramCount) batch!![i] = null
    }

    /**
     * @see java.sql.PreparedStatement.execute
     */
    @Throws(SQLException::class)
    fun execute(): Boolean {
        checkOpen()
        rs.close()
        pointer!!.safeRunConsume { obj, stmt -> obj.reset(stmt) }
        exhaustedResults = false

        if (this.conn is JDBC3Connection) {
            this.conn.tryEnforceTransactionMode()
        }

        return this.withConnectionTimeout {
            var success = false
            try {
                synchronized(conn) {
                    resultsWaiting =
                        conn.database.execute(this@JDBC3PreparedStatement, batch)
                    updateGeneratedKeys()
                    success = true
                    updateCount = database.changes()

                }
                return@withConnectionTimeout 0 != columnCount
            } finally {
                if (!success && !pointer!!.isClosed()) pointer!!.safeRunConsume { obj, stmt ->
                    obj.reset(
                        stmt
                    )
                }
            }

        }!!
    }

    /**
     * @see java.sql.PreparedStatement.executeQuery
     */
    @Throws(SQLException::class)
    fun executeQuery(): ResultSet {
        checkOpen()

        if (columnCount == 0) {
            throw SQLException("Query does not return results")
        }

        rs.close()
        pointer!!.safeRunConsume { obj, stmt -> obj.reset(stmt) }
        exhaustedResults = false

        if (this.conn is JDBC3Connection) {
            this.conn.tryEnforceTransactionMode()
        }

        return this.withConnectionTimeout<ResultSet?> {
            var success = false
            try {
                resultsWaiting =
                    conn.database.execute(this@JDBC3PreparedStatement, batch)
                success = true
            } finally {
                if (!success && !pointer!!.isClosed()) {
                    pointer!!.safeRunInt { obj, stmt ->
                        obj.reset(
                            stmt
                        )
                    }
                }
            }
            resultSet
        }!!
    }

    /**
     * @see java.sql.PreparedStatement.executeUpdate
     */
    @Throws(SQLException::class)
    fun executeUpdate(): Int {
        return executeLargeUpdate().toInt()
    }

    /**
     * @see java.sql.PreparedStatement.executeLargeUpdate
     */
    @Throws(SQLException::class)
    open fun executeLargeUpdate(): Long {
        checkOpen()

        if (columnCount != 0) {
            throw SQLException("Query returns results")
        }

        rs.close()
        pointer!!.safeRunConsume { obj, stmt -> obj.reset(stmt) }
        exhaustedResults = false

        if (this.conn is JDBC3Connection) {
            this.conn.tryEnforceTransactionMode()
        }

        return this.withConnectionTimeout {
            synchronized(conn) {
                val rc: Long =
                    conn.database
                        .executeUpdate(this@JDBC3PreparedStatement, batch)
                updateGeneratedKeys()
                return@withConnectionTimeout rc

            }
        }!!
    }

    /**
     * @see java.sql.PreparedStatement.addBatch
     */
    @Throws(SQLException::class)
    fun addBatch() {
        checkOpen()
        batchPos += paramCount
        batchQueryCount++
        if (batch == null) {
            batch = arrayOfNulls<Any>(paramCount)
        }
        if (batchPos + paramCount > batch!!.size) {
            val nb = arrayOfNulls<Any>(batch!!.size * 2)
            System.arraycopy(batch, 0, nb, 0, batch!!.size)
            batch = nb
        }
        System.arraycopy(batch, batchPos - paramCount, batch, batchPos, paramCount)
    }

    // ParameterMetaData FUNCTIONS //////////////////////////////////
    /**
     * @see java.sql.PreparedStatement.getParameterMetaData
     */
    fun getParameterMetaData(): ParameterMetaData = this as ParameterMetaData

    /**
     * @see ParameterMetaData.getParameterCount
     */
    @Throws(SQLException::class)
    fun getParameterCount(): Int {
        checkOpen()
        return paramCount
    }

    /**
     * @see ParameterMetaData.getParameterClassName
     */
    @Throws(SQLException::class)
    fun getParameterClassName(param: Int): String {
        checkOpen()
        return "java.lang.String"
    }

    /**
     * @see ParameterMetaData.getParameterTypeName
     */
    @Throws(SQLException::class)
    fun getParameterTypeName(pos: Int): String {
        checkIndex(pos)
        return JDBCType.valueOf(getParameterType(pos)).getName()
    }

    /**
     * @see ParameterMetaData.getParameterType
     */
    @Throws(SQLException::class)
    fun getParameterType(pos: Int): Int {
        checkIndex(pos)
        val paramValue = batch!![pos - 1]

        if (paramValue == null) {
            return Types.NULL
        } else if (paramValue is Int
            || paramValue is Short
            || paramValue is Boolean
        ) {
            return Types.INTEGER
        } else if (paramValue is Long) {
            return Types.BIGINT
        } else if (paramValue is Double || paramValue is Float) {
            return Types.REAL
        } else {
            return Types.VARCHAR
        }
    }

    /**
     * @see ParameterMetaData.getParameterMode
     */
    fun getParameterMode(pos: Int): Int {
        return ParameterMetaData.parameterModeIn
    }

    /**
     * @see ParameterMetaData.getPrecision
     */
    fun getPrecision(pos: Int): Int {
        return 0
    }

    /**
     * @see ParameterMetaData.getScale
     */
    fun getScale(pos: Int): Int {
        return 0
    }

    /**
     * @see ParameterMetaData.isNullable
     */
    fun isNullable(pos: Int): Int {
        return ParameterMetaData.parameterNullable
    }

    /**
     * @see ParameterMetaData.isSigned
     */
    fun isSigned(pos: Int): Boolean {
        return true
    }

    val statement: Statement
        /**
         * @return
         */
        get() = this

    /**
     * @see java.sql.PreparedStatement.setBigDecimal
     */
    @Throws(SQLException::class)
    fun setBigDecimal(pos: Int, value: BigDecimal?) {
        batch(pos, if (value == null) null else value.toString())
    }

    /**
     * Reads given number of bytes from an input stream.
     * 
     * @param istream The input stream.
     * @param length The number of bytes to read.
     * @return byte array.
     * @throws SQLException
     */
    @Throws(SQLException::class)
    private fun readBytes(istream: InputStream, length: Int): ByteArray {
        if (length < 0) {
            throw SQLException("Error reading stream. Length should be non-negative")
        }

        val bytes = ByteArray(length)

        try {
            var bytesRead: Int
            var totalBytesRead = 0

            while (totalBytesRead < length) {
                bytesRead = istream.read(bytes, totalBytesRead, length - totalBytesRead)
                if (bytesRead == -1) {
                    throw IOException("End of stream has been reached")
                }
                totalBytesRead += bytesRead
            }

            return bytes
        } catch (cause: IOException) {
            val exception = SQLException("Error reading stream")

            exception.initCause(cause)
            throw exception
        }
    }

    /**
     * @see java.sql.PreparedStatement.setBinaryStream
     */
    @Throws(SQLException::class)
    fun setBinaryStream(pos: Int, istream: InputStream?, length: Int) {
        // null 스트림은 length 와 무관하게 SQL NULL — length>0 로 흘려보내면 NPE 가 JDBC 계약을 깬다.
        if (istream == null) {
            setBytes(pos, null)
            return
        }

        setBytes(pos, readBytes(istream, length))
    }

    /**
     * @see java.sql.PreparedStatement.setAsciiStream
     */
    @Throws(SQLException::class)
    fun setAsciiStream(pos: Int, istream: InputStream?, length: Int) {
        setUnicodeStream(pos, istream, length)
    }

    /**
     * @see java.sql.PreparedStatement.setUnicodeStream
     */
    @Throws(SQLException::class)
    fun setUnicodeStream(pos: Int, istream: InputStream?, length: Int) {
        if (istream == null) {
            setString(pos, null)
            return
        }

        try {
            setString(pos, String(readBytes(istream, length), charset("UTF-8")))
        } catch (e: UnsupportedEncodingException) {
            val exception = SQLException("UTF-8 is not supported")

            exception.initCause(e)
            throw exception
        }
    }

    /**
     * @see java.sql.PreparedStatement.setBoolean
     */
    @Throws(SQLException::class)
    fun setBoolean(pos: Int, value: Boolean) {
        setInt(pos, if (value) 1 else 0)
    }

    /**
     * @see java.sql.PreparedStatement.setByte
     */
    @Throws(SQLException::class)
    fun setByte(pos: Int, value: Byte) {
        setInt(pos, value.toInt())
    }

    /**
     * @see java.sql.PreparedStatement.setBytes
     */
    @Throws(SQLException::class)
    fun setBytes(pos: Int, value: ByteArray?) {
        batch(pos, value)
    }

    /**
     * @see java.sql.PreparedStatement.setDouble
     */
    @Throws(SQLException::class)
    fun setDouble(pos: Int, value: Double) {
        batch(pos, value)
    }

    /**
     * @see java.sql.PreparedStatement.setFloat
     */
    @Throws(SQLException::class)
    fun setFloat(pos: Int, value: Float) {
        batch(pos, value)
    }

    /**
     * @see java.sql.PreparedStatement.setInt
     */
    @Throws(SQLException::class)
    fun setInt(pos: Int, value: Int) {
        batch(pos, value)
    }

    /**
     * @see java.sql.PreparedStatement.setLong
     */
    @Throws(SQLException::class)
    fun setLong(pos: Int, value: Long) {
        batch(pos, value)
    }

    /**
     * @see java.sql.PreparedStatement.setNull
     */
    @Throws(SQLException::class)
    fun setNull(pos: Int, u1: Int) {
        setNull(pos, u1, null)
    }

    /**
     * @see java.sql.PreparedStatement.setNull
     */
    @Throws(SQLException::class)
    fun setNull(pos: Int, u1: Int, u2: String?) {
        batch(pos, null)
    }

    /**
     * @see java.sql.PreparedStatement.setObject
     */
    @Throws(SQLException::class)
    fun setObject(pos: Int, value: Any?) {
        if (value == null) {
            batch(pos, null)
        } else if (value is Date) {
            setDateByMilliseconds(pos, value.getTime(), Calendar.getInstance())
        } else if (value is Long) {
            batch(pos, value)
        } else if (value is Int) {
            batch(pos, value)
        } else if (value is Short) {
            batch(pos, value.toInt())
        } else if (value is Float) {
            batch(pos, value)
        } else if (value is Double) {
            batch(pos, value)
        } else if (value is Boolean) {
            setBoolean(pos, value)
        } else if (value is ByteArray) {
            batch(pos, value)
        } else if (value is BigDecimal) {
            setBigDecimal(pos, value)
        } else {
            batch(pos, value.toString())
        }
    }

    /**
     * @see java.sql.PreparedStatement.setObject
     */
    @Throws(SQLException::class)
    fun setObject(p: Int, v: Any?, t: Int) {
        setObject(p, v)
    }

    /**
     * @see java.sql.PreparedStatement.setObject
     */
    @Throws(SQLException::class)
    fun setObject(p: Int, v: Any?, t: Int, s: Int) {
        setObject(p, v)
    }

    /**
     * @see java.sql.PreparedStatement.setShort
     */
    @Throws(SQLException::class)
    fun setShort(pos: Int, value: Short) {
        setInt(pos, value.toInt())
    }

    /**
     * @see java.sql.PreparedStatement.setString
     */
    @Throws(SQLException::class)
    fun setString(pos: Int, value: String?) {
        batch(pos, value)
    }

    /**
     * @see java.sql.PreparedStatement.setCharacterStream
     */
    @Throws(SQLException::class)
    fun setCharacterStream(pos: Int, reader: Reader, length: Int) {
        try {
            // copy chars from reader to StringBuffer
            val sb = StringBuffer()
            val cbuf = CharArray(8192)
            var cnt: Int

            while ((reader.read(cbuf).also { cnt = it }) > 0) {
                sb.append(cbuf, 0, cnt)
            }

            // set as string
            setString(pos, sb.toString())
        } catch (e: IOException) {
            throw SQLException(
                "Cannot read from character stream, exception message: " + e.message
            )
        }
    }

    /**
     * @see java.sql.PreparedStatement.setDate
     */
    @Throws(SQLException::class)
    fun setDate(pos: Int, x: java.sql.Date?) {
        setDate(pos, x, Calendar.getInstance())
    }

    /**
     * @see java.sql.PreparedStatement.setDate
     */
    @Throws(SQLException::class)
    fun setDate(pos: Int, x: java.sql.Date?, cal: Calendar) {
        if (x == null) {
            setObject(pos, null)
        } else {
            setDateByMilliseconds(pos, x.getTime(), cal)
        }
    }

    /**
     * @see java.sql.PreparedStatement.setTime
     */
    @Throws(SQLException::class)
    fun setTime(pos: Int, x: Time?) {
        setTime(pos, x, Calendar.getInstance())
    }

    /**
     * @see java.sql.PreparedStatement.setTime
     */
    @Throws(SQLException::class)
    fun setTime(pos: Int, x: Time?, cal: Calendar) {
        if (x == null) {
            setObject(pos, null)
        } else {
            setDateByMilliseconds(pos, x.getTime(), cal)
        }
    }

    /**
     * @see java.sql.PreparedStatement.setTimestamp
     */
    @Throws(SQLException::class)
    fun setTimestamp(pos: Int, x: Timestamp?) {
        setTimestamp(pos, x, Calendar.getInstance())
    }

    /**
     * @see java.sql.PreparedStatement.setTimestamp
     */
    @Throws(SQLException::class)
    fun setTimestamp(pos: Int, x: Timestamp?, cal: Calendar) {
        if (x == null) {
            setObject(pos, null)
        } else {
            setDateByMilliseconds(pos, x.getTime(), cal)
        }
    }

    /**
     * @see java.sql.PreparedStatement.getMetaData
     */
    @Throws(SQLException::class)
    fun getMetaData(): ResultSetMetaData {
            checkOpen()
            return rs as ResultSetMetaData
        }

    override fun unsupported(): SQLException {
        return SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver")
    }

    protected fun invalid(): SQLException {
        return SQLException("method cannot be called on a PreparedStatement")
    }

    // PreparedStatement ////////////////////////////////////////////
    @Throws(SQLException::class)
    fun setArray(i: Int, x: Array?) {
        if (x == null) { setBytes(i, null); return }
        val arr = x as? SQLiteArray
            ?: throw SQLException("setArray: only arrays from Connection.createArrayOf are supported")
        batch(i, arr)   // 바인딩 시점에 DB.sqlbind 가 carray_bind 로 디스패치
    }

    @Throws(SQLException::class)
    fun setBlob(i: Int, x: Blob?) {
        if (x == null) setBytes(i, null)
        else setBytes(i, x.getBytes(1, x.length().toInt()))
    }

    @Throws(SQLException::class)
    fun setClob(i: Int, x: Clob?) {
        if (x == null) setString(i, null)
        else setString(i, x.getSubString(1, x.length().toInt()))
    }

    @Throws(SQLException::class)
    fun setRef(i: Int, x: Ref) {
        throw unsupported()
    }

    @Throws(SQLException::class)
    fun setURL(pos: Int, x: URL) {
        throw unsupported()
    }

    /**
     * @see CoreStatement.exec
     */
    @Throws(SQLException::class)
    override fun execute(sql: String): Boolean {
        throw invalid()
    }

    @Throws(SQLException::class)
    override fun execute(sql: String, autoGeneratedKeys: Int): Boolean {
        throw invalid()
    }

    @Throws(SQLException::class)
    override fun execute(sql: String, colinds: IntArray): Boolean {
        throw invalid()
    }

    @Throws(SQLException::class)
    override fun execute(sql: String, colnames: kotlin.Array<String>): Boolean {
        throw invalid()
    }

    /**
     * @see CoreStatement.exec
     */
    @Throws(SQLException::class)
    override fun executeUpdate(sql: String): Int {
        throw invalid()
    }

    @Throws(SQLException::class)
    override fun executeUpdate(sql: String, autoGeneratedKeys: Int): Int {
        throw invalid()
    }

    @Throws(SQLException::class)
    override fun executeUpdate(sql: String, colinds: IntArray): Int {
        throw invalid()
    }

    @Throws(SQLException::class)
    override fun executeUpdate(sql: String, cols: kotlin.Array<String>): Int {
        throw invalid()
    }

    @Throws(SQLException::class)
    override fun executeLargeUpdate(sql: String): Long {
        throw invalid()
    }

    @Throws(SQLException::class)
    override fun executeLargeUpdate(sql: String, autoGeneratedKeys: Int): Long {
        throw invalid()
    }

    @Throws(SQLException::class)
    override fun executeLargeUpdate(sql: String, colinds: IntArray): Long {
        throw invalid()
    }

    @Throws(SQLException::class)
    override fun executeLargeUpdate(sql: String, cols: kotlin.Array<String>): Long {
        throw invalid()
    }

    /**
     * @see CoreStatement.exec
     */
    @Throws(SQLException::class)
    override fun executeQuery(sql: String): ResultSet {
        throw invalid()
    }

    /**  */
    @Throws(SQLException::class)
    override fun addBatch(sql: String) {
        throw invalid()
    }
}
