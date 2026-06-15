package org.example.sqlite.jdbc.jdbc3

import org.example.sqlite.jdbc.core.Codes
import org.example.sqlite.jdbc.core.CoreResultSet
import org.example.sqlite.jdbc.core.CoreStatement
import org.example.sqlite.jdbc.core.SafeStmtPtr.*
import org.example.sqlite.jdbc.date.FastDateFormat.Companion.getInstance
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import java.math.BigDecimal
import java.sql.*
import java.sql.Date
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

abstract class JDBC3ResultSet  // ResultSet Functions //////////////////////////////////////////
protected constructor(stmt: CoreStatement) : CoreResultSet(stmt) {
    /**
     * returns col in [1,x] form
     * 
     * @see ResultSet.findColumn
     */
    @Throws(SQLException::class)
    fun findColumn(col: String): Int {
        checkOpen()
        val index = findColumnIndexInCache(col)
        if (index != null) {
            return index
        }
        for (i in cols!!.indices) {
            if (col.equals(cols!![i], ignoreCase = true)) {
                return addColumnIndexInCache(col, i + 1)
            }
        }
        throw SQLException("no such column: '" + col + "'")
    }

    /**
     * @see ResultSet.next
     */
    @Throws(SQLException::class)
    fun next(): Boolean {
        if (!open || emptyResultSet || pastLastRow) {
            return false // finished ResultSet
        }
        lastCol = -1

        // first row is loaded by execute(), so do not step() again
        if (row == 0) {
            row++
            return true
        }

        // check if we are row limited by the statement or the ResultSet
        if (maxRows != 0L && row.toLong() == maxRows) {
            return false
        }

        // do the real work
        val statusCode =
            stmt.pointer!!.safeRunInt<SQLException> { obj, stmt: Long -> obj.step(stmt) }
        when (statusCode) {
            Codes.SQLITE_DONE -> {
                pastLastRow = true
                return false
            }

            Codes.SQLITE_ROW -> {
                row++
                return true
            }

            Codes.SQLITE_BUSY -> {
                database.throwex(statusCode)
                return false
            }

            else -> {
                database.throwex(statusCode)
                return false
            }
        }
    }

    /**
     * @see ResultSet.getType
     */
    fun getType(): Int = ResultSet.TYPE_FORWARD_ONLY

    /**
     * @see ResultSet.getFetchSize
     */
    fun getFetchSize(): Int = limitRows

    /**
     * @see ResultSet.setFetchSize
     */
    @Throws(SQLException::class)
    fun setFetchSize(rows: Int) {
        if (0 > rows || (maxRows != 0L && rows > maxRows)) {
            throw SQLException("fetch size " + rows + " out of bounds " + maxRows)
        }
        limitRows = rows
    }

    /**
     * @see ResultSet.getFetchDirection
     */
    @Throws(SQLException::class)
    fun getFetchDirection(): Int {
        checkOpen()
        return ResultSet.FETCH_FORWARD
    }

    /**
     * @see ResultSet.setFetchDirection
     */
    @Throws(SQLException::class)
    fun setFetchDirection(d: Int) {
        checkOpen()
        // Only FORWARD_ONLY ResultSets exist in SQLite, so only FETCH_FORWARD is permitted
        if ( /*getType() == ResultSet.TYPE_FORWARD_ONLY &&*/d != ResultSet.FETCH_FORWARD) {
            throw SQLException("only FETCH_FORWARD direction supported")
        }
    }

    /**
     * @see ResultSet.isAfterLast
     */
    fun isAfterLast(): Boolean = pastLastRow && !emptyResultSet

    /**
     * @see ResultSet.isBeforeFirst
     */
    fun isBeforeFirst(): Boolean = !emptyResultSet && open && row == 0

    /**
     * @see ResultSet.isFirst
     */
    fun isFirst(): Boolean = row == 1

    /**
     * @see ResultSet.isLast
     */
    @Throws(SQLException::class)
    fun isLast(): Boolean {
        throw SQLFeatureNotSupportedException("not supported by sqlite")
    }

    /**
     * @see ResultSet.getRow
     */
    fun getRow(): Int {
        return row
    }



    /**
     * @see ResultSet.wasNull
     */
    @Throws(SQLException::class)
    fun wasNull(): Boolean {
        return safeGetColumnType(markCol(lastCol)) == Codes.SQLITE_NULL
    }

    // DATA ACCESS FUNCTIONS ////////////////////////////////////////
    /**
     * @see ResultSet.getBigDecimal
     */
    @Throws(SQLException::class)
    fun getBigDecimal(col: Int): BigDecimal? {
        when (safeGetColumnType(checkCol(col))) {
            Codes.SQLITE_NULL -> return null
            Codes.SQLITE_INTEGER -> return BigDecimal.valueOf(safeGetLongCol(col))
            Codes.SQLITE_FLOAT -> {
                val stringValue = safeGetColumnText(col)
                try {
                    return BigDecimal(stringValue)
                } catch (e: NumberFormatException) {
                    throw SQLException("Bad value for type BigDecimal : " + stringValue)
                }
            }

            else -> {
                val stringValue = safeGetColumnText(col)
                try {
                    return BigDecimal(stringValue)
                } catch (e: NumberFormatException) {
                    throw SQLException("Bad value for type BigDecimal : " + stringValue)
                }
            }
        }
    }

    /**
     * @see ResultSet.getBigDecimal
     */
    @Throws(SQLException::class)
    fun getBigDecimal(col: String): BigDecimal? {
        return getBigDecimal(findColumn(col))
    }

    /**
     * @see ResultSet.getBoolean
     */
    @Throws(SQLException::class)
    fun getBoolean(col: Int): Boolean {
        return getInt(col) != 0
    }

    /**
     * @see ResultSet.getBoolean
     */
    @Throws(SQLException::class)
    fun getBoolean(col: String): Boolean {
        return getBoolean(findColumn(col))
    }

    /**
     * @see ResultSet.getBinaryStream
     */
    @Throws(SQLException::class)
    fun getBinaryStream(col: Int): InputStream? {
        val bytes = getBytes(col)
        if (bytes != null) {
            return ByteArrayInputStream(bytes)
        } else {
            return null
        }
    }

    /**
     * @see ResultSet.getBinaryStream
     */
    @Throws(SQLException::class)
    fun getBinaryStream(col: String): InputStream? {
        return getBinaryStream(findColumn(col))
    }

    /**
     * @see ResultSet.getByte
     */
    @Throws(SQLException::class)
    fun getByte(col: Int): Byte {
        return getInt(col).toByte()
    }

    /**
     * @see ResultSet.getByte
     */
    @Throws(SQLException::class)
    fun getByte(col: String): Byte {
        return getByte(findColumn(col))
    }



    /**
     * @see ResultSet.getBytes
     */
    @Throws(SQLException::class)
    fun getBytes(col: Int): ByteArray? {
        return stmt.pointer!!.safeRun<ByteArray?, SQLException> { db, ptr ->
            db.column_blob(
                ptr,
                markCol(col)
            )
        }
    }

    /**
     * @see ResultSet.getBytes
     */
    @Throws(SQLException::class)
    fun getBytes(col: String): ByteArray? {
        return getBytes(findColumn(col))
    }

    /**
     * @see ResultSet.getCharacterStream
     */
    @Throws(SQLException::class)
    fun getCharacterStream(col: Int): Reader? {
        val string = getString(col)
        return if (string == null) null else StringReader(string)
    }

    /**
     * @see ResultSet.getCharacterStream
     */
    @Throws(SQLException::class)
    fun getCharacterStream(col: String): Reader? {
        return getCharacterStream(findColumn(col))
    }

    /**
     * @see ResultSet.getDate
     */
    @Throws(SQLException::class)
    fun getDate(col: Int): Date? {
        when (safeGetColumnType(markCol(col))) {
            Codes.SQLITE_NULL -> return null

            Codes.SQLITE_TEXT -> {
                val dateText = safeGetColumnText(col)
                if ("" == dateText) {
                    return null
                }
                try {
                    return Date(
                        connectionConfig.dateFormat.parse(dateText!!)!!.getTime()
                    )
                } catch (e: Exception) {
                    throw SQLException("Error parsing date", e)
                }

            }

            Codes.SQLITE_FLOAT -> return Date(julianDateToCalendar(safeGetDoubleCol(col))!!.getTimeInMillis())

            else -> return Date(safeGetLongCol(col) * connectionConfig.dateMultiplier)
        }
    }

    /**
     * @see ResultSet.getDate
     */
    @Throws(SQLException::class)
    fun getDate(col: Int, cal: Calendar): Date? {
        requireCalendarNotNull(cal)
        when (safeGetColumnType(markCol(col))) {
            Codes.SQLITE_NULL -> return null

            Codes.SQLITE_TEXT -> {
                val dateText = safeGetColumnText(col)
                if ("" == dateText) {
                    return null
                }
                try {
                    val dateFormat =
                        getInstance(
                            connectionConfig.dateStringFormat, cal.getTimeZone()
                        )

                    return Date(dateFormat.parse(dateText!!)!!.getTime())
                } catch (e: Exception) {
                    throw SQLException("Error parsing time stamp", e)
                }

            }

            Codes.SQLITE_FLOAT -> return Date(julianDateToCalendar(safeGetDoubleCol(col), cal)!!.getTimeInMillis())

            else -> {
                cal.setTimeInMillis(
                    safeGetLongCol(col) * connectionConfig.dateMultiplier
                )
                return Date(cal.getTime().getTime())
            }
        }
    }

    /**
     * @see ResultSet.getDate
     */
    @Throws(SQLException::class)
    fun getDate(col: String): Date? {
        return getDate(findColumn(col), Calendar.getInstance())
    }

    /**
     * @see ResultSet.getDate
     */
    @Throws(SQLException::class)
    fun getDate(col: String, cal: Calendar): Date? {
        return getDate(findColumn(col), cal)
    }

    /**
     * @see ResultSet.getDouble
     */
    @Throws(SQLException::class)
    fun getDouble(col: Int): Double {
        if (safeGetColumnType(markCol(col)) == Codes.SQLITE_NULL) {
            return 0.0
        }
        return safeGetDoubleCol(col)
    }

    /**
     * @see ResultSet.getDouble
     */
    @Throws(SQLException::class)
    fun getDouble(col: String): Double {
        return getDouble(findColumn(col))
    }

    /**
     * @see ResultSet.getFloat
     */
    @Throws(SQLException::class)
    fun getFloat(col: Int): Float {
        if (safeGetColumnType(markCol(col)) == Codes.SQLITE_NULL) {
            return 0f
        }
        return safeGetDoubleCol(col).toFloat()
    }

    /**
     * @see ResultSet.getFloat
     */
    @Throws(SQLException::class)
    fun getFloat(col: String): Float {
        return getFloat(findColumn(col))
    }

    /**
     * @see ResultSet.getInt
     */
    @Throws(SQLException::class)
    fun getInt(col: Int): Int {
        return stmt.pointer!!.safeRunInt<SQLException> { db, ptr: Long ->
            db.column_int(
                ptr,
                markCol(col)
            )
        }
    }

    /**
     * @see ResultSet.getInt
     */
    @Throws(SQLException::class)
    fun getInt(col: String): Int {
        return getInt(findColumn(col))
    }

    /**
     * @see ResultSet.getLong
     */
    @Throws(SQLException::class)
    fun getLong(col: Int): Long {
        return safeGetLongCol(col)
    }

    /**
     * @see ResultSet.getLong
     */
    @Throws(SQLException::class)
    fun getLong(col: String): Long {
        return getLong(findColumn(col))
    }

    /**
     * @see ResultSet.getShort
     */
    @Throws(SQLException::class)
    fun getShort(col: Int): Short {
        return getInt(col).toShort()
    }

    /**
     * @see ResultSet.getShort
     */
    @Throws(SQLException::class)
    fun getShort(col: String): Short {
        return getShort(findColumn(col))
    }

    /**
     * @see ResultSet.getString
     */
    @Throws(SQLException::class)
    fun getString(col: Int): String? {
        return safeGetColumnText(col)
    }

    /**
     * @see ResultSet.getString
     */
    @Throws(SQLException::class)
    fun getString(col: String): String? {
        return getString(findColumn(col))
    }

    /**
     * @see ResultSet.getTime
     */
    @Throws(SQLException::class)
    fun getTime(col: Int): Time? {
        when (safeGetColumnType(markCol(col))) {
            Codes.SQLITE_NULL -> return null

            Codes.SQLITE_TEXT -> {
                val dateText = safeGetColumnText(col)
                if ("" == dateText) {
                    return null
                }
                try {
                    return Time(
                        connectionConfig.dateFormat.parse(dateText!!)!!.getTime()
                    )
                } catch (e: Exception) {
                    throw SQLException("Error parsing time", e)
                }

            }

            Codes.SQLITE_FLOAT -> return Time(julianDateToCalendar(safeGetDoubleCol(col))!!.getTimeInMillis())

            else -> return Time(safeGetLongCol(col) * connectionConfig.dateMultiplier)
        }
    }

    /**
     * @see ResultSet.getTime
     */
    @Throws(SQLException::class)
    fun getTime(col: Int, cal: Calendar): Time? {
        requireCalendarNotNull(cal)
        when (safeGetColumnType(markCol(col))) {
            Codes.SQLITE_NULL -> return null

            Codes.SQLITE_TEXT -> {
                val dateText = safeGetColumnText(col)
                if ("" == dateText) {
                    return null
                }
                try {
                    val dateFormat =
                        getInstance(
                            connectionConfig.dateStringFormat, cal.getTimeZone()
                        )

                    return Time(dateFormat.parse(dateText!!)!!.getTime())
                } catch (e: Exception) {
                    throw SQLException("Error parsing time", e)
                }

            }

            Codes.SQLITE_FLOAT -> return Time(julianDateToCalendar(safeGetDoubleCol(col), cal)!!.getTimeInMillis())

            else -> {
                cal.setTimeInMillis(
                    safeGetLongCol(col) * connectionConfig.dateMultiplier
                )
                return Time(cal.getTime().getTime())
            }
        }
    }

    /**
     * @see ResultSet.getTime
     */
    @Throws(SQLException::class)
    fun getTime(col: String): Time? {
        return getTime(findColumn(col))
    }

    /**
     * @see ResultSet.getTime
     */
    @Throws(SQLException::class)
    fun getTime(col: String, cal: Calendar): Time? {
        return getTime(findColumn(col), cal)
    }

    /**
     * @see ResultSet.getTimestamp
     */
    @Throws(SQLException::class)
    fun getTimestamp(col: Int): Timestamp? {
        when (safeGetColumnType(markCol(col))) {
            Codes.SQLITE_NULL -> return null

            Codes.SQLITE_TEXT -> {
                val dateText = safeGetColumnText(col)
                if ("" == dateText) {
                    return null
                }
                try {
                    return Timestamp(
                        connectionConfig.dateFormat.parse(dateText!!)!!.getTime()
                    )
                } catch (e: Exception) {
                    throw SQLException("Error parsing time stamp", e)
                }

            }

            Codes.SQLITE_FLOAT -> return Timestamp(julianDateToCalendar(safeGetDoubleCol(col))!!.getTimeInMillis())

            else -> return Timestamp(
                safeGetLongCol(col) * connectionConfig.dateMultiplier
            )
        }
    }

    /**
     * @see ResultSet.getTimestamp
     */
    @Throws(SQLException::class)
    fun getTimestamp(col: Int, cal: Calendar): Timestamp? {
        requireCalendarNotNull(cal)
        when (safeGetColumnType(markCol(col))) {
            Codes.SQLITE_NULL -> return null

            Codes.SQLITE_TEXT -> {
                val dateText = safeGetColumnText(col)
                if ("" == dateText) {
                    return null
                }
                try {
                    val dateFormat =
                        getInstance(
                            connectionConfig.dateStringFormat, cal.getTimeZone()
                        )

                    return Timestamp(dateFormat.parse(dateText!!)!!.getTime())
                } catch (e: Exception) {
                    throw SQLException("Error parsing time stamp", e)
                }

            }

            Codes.SQLITE_FLOAT -> return Timestamp(julianDateToCalendar(safeGetDoubleCol(col))!!.getTimeInMillis())

            else -> {
                cal.setTimeInMillis(
                    safeGetLongCol(col) * connectionConfig.dateMultiplier
                )

                return Timestamp(cal.getTime().getTime())
            }
        }
    }

    /**
     * @see ResultSet.getTimestamp
     */
    @Throws(SQLException::class)
    fun getTimestamp(col: String): Timestamp? {
        return getTimestamp(findColumn(col))
    }

    /**
     * @see ResultSet.getTimestamp
     */
    @Throws(SQLException::class)
    fun getTimestamp(c: String, ca: Calendar): Timestamp? {
        return getTimestamp(findColumn(c), ca)
    }

    /**
     * @see ResultSet.getObject
     */
    @Throws(SQLException::class)
    fun getObject(col: Int): Any? {
        when (safeGetColumnType(markCol(col))) {
            Codes.SQLITE_INTEGER -> {
                val `val` = getLong(col)
                if (`val` > Int.MAX_VALUE || `val` < Int.MIN_VALUE) {
                    return `val`
                } else {
                    return `val`.toInt()
                }
            }

            Codes.SQLITE_FLOAT -> return getDouble(col)
            Codes.SQLITE_BLOB -> return getBytes(col)
            Codes.SQLITE_NULL -> return null
            Codes.SQLITE_TEXT -> return getString(col)
            else -> return getString(col)
        }
    }

    /**
     * @see ResultSet.getObject
     */
    @Throws(SQLException::class)
    fun getObject(col: String): Any? {
        return getObject(findColumn(col))
    }

    /**
     * @see ResultSet.getStatement
     */
    fun getStatement(): Statement = stmt as Statement

    /**
     * @see ResultSet.getCursorName
     */
    fun getCursorName(): String? = null

    /**
     * @see ResultSet.getWarnings
     */
    fun getWarnings(): SQLWarning? = null

    /**
     * @see ResultSet.clearWarnings
     */
    fun clearWarnings() {}

    // we do not need to check the RS is open, only that colsMeta
    // is not null, done with checkCol(int).
    /**
     * @see ResultSet.getMetaData
     */
    fun getMetaData(): ResultSetMetaData = this as ResultSetMetaData

    /**
     * @see ResultSetMetaData.getCatalogName
     */
    @Throws(SQLException::class)
    fun getCatalogName(col: Int): String? {
        return safeGetColumnTableName(col)
    }

    /**
     * @see ResultSetMetaData.getColumnClassName
     */
    @Throws(SQLException::class)
    fun getColumnClassName(col: Int): String {
        when (safeGetColumnType(markCol(col))) {
            Codes.SQLITE_INTEGER -> {
                val `val` = getLong(col)
                if (`val` > Int.MAX_VALUE || `val` < Int.MIN_VALUE) {
                    return "java.lang.Long"
                } else {
                    return "java.lang.Integer"
                }
            }

            Codes.SQLITE_FLOAT -> return "java.lang.Double"
            Codes.SQLITE_BLOB, Codes.SQLITE_NULL -> return "java.lang.Object"
            Codes.SQLITE_TEXT -> return "java.lang.String"
            else -> return "java.lang.String"
        }
    }

    /**
     * @see ResultSetMetaData.getColumnCount
     */
    @Throws(SQLException::class)
    fun getColumnCount(): Int {
        checkCol(1)
        return colsMeta!!.size
    }

    /**
     * @see ResultSetMetaData.getColumnDisplaySize
     */
    fun getColumnDisplaySize(col: Int): Int {
        return Int.MAX_VALUE
    }

    /**
     * @see ResultSetMetaData.getColumnLabel
     */
    @Throws(SQLException::class)
    fun getColumnLabel(col: Int): String {
        return getColumnName(col)
    }

    /**
     * @see ResultSetMetaData.getColumnName
     */
    @Throws(SQLException::class)
    fun getColumnName(col: Int): String {
        return safeGetColumnName(col)
    }

    /**
     * @see ResultSetMetaData.getColumnType
     */
    @Throws(SQLException::class)
    fun getColumnType(col: Int): Int {
        val typeName = getColumnTypeName(col)
        val valueType = safeGetColumnType(checkCol(col))

        if (valueType == Codes.SQLITE_INTEGER || valueType == Codes.SQLITE_NULL) {
            if ("BOOLEAN" == typeName) {
                return Types.BOOLEAN
            }

            if ("TINYINT" == typeName) {
                return Types.TINYINT
            }

            if ("SMALLINT" == typeName || "INT2" == typeName) {
                return Types.SMALLINT
            }

            if ("BIGINT" == typeName
                || "INT8" == typeName
                || "UNSIGNED BIG INT" == typeName
            ) {
                return Types.BIGINT
            }

            if ("DATE" == typeName || "DATETIME" == typeName) {
                return Types.DATE
            }

            if ("TIMESTAMP" == typeName) {
                return Types.TIMESTAMP
            }

            if (valueType == Codes.SQLITE_INTEGER || "INT" == typeName
                || "INTEGER" == typeName
                || "MEDIUMINT" == typeName
            ) {
                val `val` = getLong(col)
                if (`val` > Int.MAX_VALUE || `val` < Int.MIN_VALUE) {
                    return Types.BIGINT
                } else {
                    return Types.INTEGER
                }
            }
        }

        if (valueType == Codes.SQLITE_FLOAT || valueType == Codes.SQLITE_NULL) {
            if ("DECIMAL" == typeName) {
                return Types.DECIMAL
            }

            if ("DOUBLE" == typeName || "DOUBLE PRECISION" == typeName) {
                return Types.DOUBLE
            }

            if ("NUMERIC" == typeName) {
                return Types.NUMERIC
            }

            if ("REAL" == typeName) {
                return Types.REAL
            }

            if (valueType == Codes.SQLITE_FLOAT || "FLOAT" == typeName) {
                return Types.FLOAT
            }
        }

        if (valueType == Codes.SQLITE_TEXT || valueType == Codes.SQLITE_NULL) {
            if ("CHARACTER" == typeName
                || "NCHAR" == typeName
                || "NATIVE CHARACTER" == typeName
                || "CHAR" == typeName
            ) {
                return Types.CHAR
            }

            if ("CLOB" == typeName) {
                return Types.CLOB
            }

            if ("DATE" == typeName || "DATETIME" == typeName) {
                return Types.DATE
            }

            if ("TIMESTAMP" == typeName) {
                return Types.TIMESTAMP
            }

            if (valueType == Codes.SQLITE_TEXT || "VARCHAR" == typeName
                || "VARYING CHARACTER" == typeName
                || "NVARCHAR" == typeName
                || "TEXT" == typeName
            ) {
                return Types.VARCHAR
            }
        }

        if (valueType == Codes.SQLITE_BLOB || valueType == Codes.SQLITE_NULL) {
            if ("BINARY" == typeName) {
                return Types.BINARY
            }

            if (valueType == Codes.SQLITE_BLOB || "BLOB" == typeName) {
                return Types.BLOB
            }
        }

        return Types.NUMERIC
    }

    /**
     * @return The data type from either the 'create table' statement, or CAST(expr AS TYPE)
     * otherwise sqlite3_value_type.
     * @see ResultSetMetaData.getColumnTypeName
     */
    @Throws(SQLException::class)
    fun getColumnTypeName(col: Int): String {
        val declType = getColumnDeclType(col)

        if (declType != null) {
            val matcher: Matcher = COLUMN_TYPENAME.matcher(declType)

            matcher.find()
            return matcher.group(1).uppercase()
        }

        when (safeGetColumnType(checkCol(col))) {
            Codes.SQLITE_INTEGER -> return "INTEGER"
            Codes.SQLITE_FLOAT -> return "FLOAT"
            Codes.SQLITE_BLOB -> return "BLOB"
            Codes.SQLITE_TEXT -> return "TEXT"
            Codes.SQLITE_NULL -> return "NUMERIC"
            else -> return "NUMERIC"
        }
    }

    /**
     * @see ResultSetMetaData.getPrecision
     */
    @Throws(SQLException::class)
    fun getPrecision(col: Int): Int {
        val declType = getColumnDeclType(col)

        if (declType != null) {
            val matcher: Matcher = COLUMN_PRECISION.matcher(declType)

            return if (matcher.find()) matcher.group(1).split(",".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()[0].trim { it <= ' ' }.toInt() else 0
        }

        return 0
    }

    @Throws(SQLException::class)
    private fun getColumnDeclType(col: Int): String? {
        var declType: String? = stmt.pointer!!.safeRun<String?, SQLException> { db, ptr ->
            db.column_decltype(
                ptr,
                checkCol(col)
            )
        }

        if (declType == null) {
            val matcher: Matcher = COLUMN_TYPECAST.matcher(safeGetColumnName(col))
            declType = if (matcher.find()) matcher.group(1) else null
        }

        return declType
    }

    /**
     * @see ResultSetMetaData.getScale
     */
    @Throws(SQLException::class)
    fun getScale(col: Int): Int {
        val declType = getColumnDeclType(col)

        if (declType != null) {
            val matcher: Matcher = COLUMN_PRECISION.matcher(declType)

            if (matcher.find()) {
                val array = matcher.group(1).split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

                if (array.size == 2) {
                    return array[1].trim { it <= ' ' }.toInt()
                }
            }
        }

        return 0
    }

    /**
     * @see ResultSetMetaData.getSchemaName
     */
    fun getSchemaName(col: Int): String {
        return ""
    }

    /**
     * @see ResultSetMetaData.getTableName
     */
    @Throws(SQLException::class)
    fun getTableName(col: Int): String {
        val tableName = safeGetColumnTableName(col)
        if (tableName == null) {
            // JDBC specifies an empty string instead of null
            return ""
        }
        return tableName
    }

    /**
     * @see ResultSetMetaData.isNullable
     */
    @Throws(SQLException::class)
    fun isNullable(col: Int): Int {
        checkMeta()
        return if (meta!![checkCol(col)][0])
            ResultSetMetaData.columnNoNulls
        else
            ResultSetMetaData.columnNullable
    }

    /**
     * @see ResultSetMetaData.isAutoIncrement
     */
    @Throws(SQLException::class)
    fun isAutoIncrement(col: Int): Boolean {
        checkMeta()
        return meta!![checkCol(col)][2]
    }

    /**
     * @see ResultSetMetaData.isCaseSensitive
     */
    fun isCaseSensitive(col: Int): Boolean {
        return true
    }

    /**
     * @see ResultSetMetaData.isCurrency
     */
    fun isCurrency(col: Int): Boolean {
        return false
    }

    /**
     * @see ResultSetMetaData.isDefinitelyWritable
     */
    fun isDefinitelyWritable(col: Int): Boolean {
        return true
    } // FIXME: check db file constraints?

    /**
     * @see ResultSetMetaData.isReadOnly
     */
    fun isReadOnly(col: Int): Boolean {
        return false
    }

    /**
     * @see ResultSetMetaData.isSearchable
     */
    fun isSearchable(col: Int): Boolean {
        return true
    }

    /**
     * @see ResultSetMetaData.isSigned
     */
    @Throws(SQLException::class)
    fun isSigned(col: Int): Boolean {
        val typeName = getColumnTypeName(col)

        return "NUMERIC" == typeName || "INTEGER" == typeName || "REAL" == typeName
    }

    /**
     * @see ResultSetMetaData.isWritable
     */
    fun isWritable(col: Int): Boolean {
        return true
    }

    /**
     * @see ResultSet.getConcurrency
     */
    fun getConcurrency(): Int = ResultSet.CONCUR_READ_ONLY

    /**
     * @see ResultSet.rowDeleted
     */
    fun rowDeleted(): Boolean {
        return false
    }

    /**
     * @see ResultSet.rowInserted
     */
    fun rowInserted(): Boolean {
        return false
    }

    /**
     * @see ResultSet.rowUpdated
     */
    fun rowUpdated(): Boolean {
        return false
    }

    /**
     * Transforms a Julian Date to java.util.Calendar object. Based on Guine Christian's function
     * found here:
     * http://java.ittoolbox.com/groups/technical-functional/java-l/java-function-to-convert-julian-date-to-calendar-date-1947446
     */
    /** Transforms a Julian Date to java.util.Calendar object.  */
    private fun julianDateToCalendar(jd: Double?, cal: Calendar = Calendar.getInstance()): Calendar? {
        if (jd == null) {
            return null
        }

        val yyyy: Int
        val dd: Int
        val mm: Int
        val hh: Int
        val mn: Int
        val ss: Int
        val ms: Int
        val A: Int

        val w = jd + 0.5
        val Z = w.toInt()
        val F = w - Z

        if (Z < 2299161) {
            A = Z
        } else {
            val alpha = ((Z - 1867216.25) / 36524.25).toInt()
            A = Z + 1 + alpha - (alpha / 4.0).toInt()
        }

        val B = A + 1524
        val C = ((B - 122.1) / 365.25).toInt()
        val D = (365.25 * C).toInt()
        val E = ((B - D) / 30.6001).toInt()

        //  month
        mm = E - (if (E < 13.5) 1 else 13)

        // year
        yyyy = C - (if (mm > 2.5) 4716 else 4715)

        // Day
        val jjd = B - D - (30.6001 * E).toInt() + F
        dd = jjd.toInt()

        // Hour
        val hhd = jjd - dd
        hh = (24 * hhd).toInt()

        // Minutes
        val mnd = (24 * hhd) - hh
        mn = (60 * mnd).toInt()

        // Seconds
        val ssd = (60 * mnd) - mn
        ss = (60 * ssd).toInt()

        // Milliseconds
        val msd = (60 * ssd) - ss
        ms = (1000 * msd).toInt()

        cal.set(yyyy, mm - 1, dd, hh, mn, ss)
        cal.set(Calendar.MILLISECOND, ms)

        if (yyyy < 1) {
            cal.set(Calendar.ERA, GregorianCalendar.BC)
            cal.set(Calendar.YEAR, -(yyyy - 1))
        }

        return cal
    }

    @Throws(SQLException::class)
    private fun requireCalendarNotNull(cal: Calendar) {
        if (cal == null) {
            throw SQLException("Expected a calendar instance.", IllegalArgumentException())
        }
    }

    @Throws(SQLException::class)
    protected fun safeGetColumnType(col: Int): Int {
        return stmt.pointer!!.safeRunInt<SQLException> { db, ptr: Long ->
            db.column_type(
                ptr,
                col
            )
        }
    }

    @Throws(SQLException::class)
    private fun safeGetLongCol(col: Int): Long {
        return stmt.pointer!!.safeRunLong<SQLException> { db, ptr: Long ->
            db.column_long(
                ptr,
                markCol(col)
            )
        }
    }

    @Throws(SQLException::class)
    private fun safeGetDoubleCol(col: Int): Double {
        return stmt.pointer!!.safeRunDouble<SQLException> { db, ptr: Long ->
            db.column_double(
                ptr,
                markCol(col)
            )
        }
    }

    @Throws(SQLException::class)
    private fun safeGetColumnText(col: Int): String? {
        return stmt.pointer!!.safeRun<String?, SQLException> { db, ptr: Long ->
            db.column_text(
                ptr,
                markCol(col)
            )
        }
    }

    @Throws(SQLException::class)
    private fun safeGetColumnTableName(col: Int): String? {
        return stmt.pointer!!.safeRun<String?, SQLException> { db, ptr: Long ->
            db!!.column_table_name(
                ptr,
                checkCol(col)
            )
        }
    }

    @Throws(SQLException::class)
    private fun safeGetColumnName(col: Int): String {
        return stmt.pointer!!.safeRun<String, SQLException> { db, ptr: Long ->
            db.column_name(
                ptr,
                checkCol(col)
            )!!
        }
    }

    companion object {
        // ResultSetMetaData Functions //////////////////////////////////
        /** Pattern used to extract the column type name from table column definition.  */
        protected val COLUMN_TYPENAME: Pattern = Pattern.compile("([^\\(]*)")

        /** Pattern used to extract the column type name from a cast(col as type)  */
        protected val COLUMN_TYPECAST: Pattern = Pattern.compile("cast\\(.*?\\s+as\\s+(.*?)\\s*\\)")

        /**
         * Pattern used to extract the precision and scale from column meta returned by the JDBC driver.
         */
        protected val COLUMN_PRECISION: Pattern = Pattern.compile(".*?\\((.*?)\\)")
    }
}
