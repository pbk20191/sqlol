package io.roastedroot.sqlite4j

import io.roastedroot.sqlite4j.date.FastDateFormat
import java.sql.Connection
import java.util.EnumMap
import java.util.Objects
import java.util.Properties

/** Connection local configurations */
class SQLiteConnectionConfig(
    var dateClass: SQLiteConfig.DateClass,
    var datePrecision: SQLiteConfig.DatePrecision,
    dateStringFormat: String,
    var transactionIsolation: Int,
    var transactionMode: SQLiteConfig.TransactionMode,
    var isAutoCommit: Boolean,
    var isGetGeneratedKeys: Boolean
) : Cloneable {
    var dateStringFormat: String = dateStringFormat
        set(value) {
            field = value
            dateFormat = FastDateFormat.getInstance(value)
        }

    var dateFormat: FastDateFormat = FastDateFormat.getInstance(dateStringFormat)
        private set

    fun copyConfig(): SQLiteConnectionConfig {
        return SQLiteConnectionConfig(
            dateClass,
            datePrecision,
            dateStringFormat,
            transactionIsolation,
            transactionMode,
            isAutoCommit,
            isGetGeneratedKeys
        )
    }

    val dateMultiplier: Long
        get() = if (datePrecision == SQLiteConfig.DatePrecision.MILLISECONDS) 1L else 1000L

    fun transactionPrefix(): String? {
        return beginCommandMap[transactionMode]
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o !is SQLiteConnectionConfig) return false
        return transactionIsolation == o.transactionIsolation &&
            isAutoCommit == o.isAutoCommit &&
            isGetGeneratedKeys == o.isGetGeneratedKeys &&
            dateClass == o.dateClass &&
            datePrecision == o.datePrecision &&
            Objects.equals(dateStringFormat, o.dateStringFormat) &&
            Objects.equals(dateFormat, o.dateFormat) &&
            transactionMode == o.transactionMode
    }

    override fun hashCode(): Int {
        return Objects.hash(
            dateClass,
            datePrecision,
            dateStringFormat,
            dateFormat,
            transactionIsolation,
            transactionMode,
            isAutoCommit,
            isGetGeneratedKeys
        )
    }

    companion object {
        private val beginCommandMap: MutableMap<SQLiteConfig.TransactionMode, String> =
            EnumMap(SQLiteConfig.TransactionMode::class.java)

        init {
            beginCommandMap[SQLiteConfig.TransactionMode.DEFERRED] = "begin;"
            beginCommandMap[SQLiteConfig.TransactionMode.IMMEDIATE] = "begin immediate;"
            beginCommandMap[SQLiteConfig.TransactionMode.EXCLUSIVE] = "begin exclusive;"
        }

        @JvmStatic
        fun fromPragmaTable(pragmaTable: Properties): SQLiteConnectionConfig {
            return SQLiteConnectionConfig(
                SQLiteConfig.DateClass.getDateClass(
                    pragmaTable.getProperty(
                        SQLiteConfig.Pragma.DATE_CLASS.pragmaName,
                        SQLiteConfig.DateClass.INTEGER.name
                    )
                ),
                SQLiteConfig.DatePrecision.getPrecision(
                    pragmaTable.getProperty(
                        SQLiteConfig.Pragma.DATE_PRECISION.pragmaName,
                        SQLiteConfig.DatePrecision.MILLISECONDS.name
                    )
                ),
                pragmaTable.getProperty(
                    SQLiteConfig.Pragma.DATE_STRING_FORMAT.pragmaName,
                    SQLiteConfig.DEFAULT_DATE_STRING_FORMAT
                ),
                Connection.TRANSACTION_SERIALIZABLE,
                SQLiteConfig.TransactionMode.getMode(
                    pragmaTable.getProperty(
                        SQLiteConfig.Pragma.TRANSACTION_MODE.pragmaName,
                        SQLiteConfig.TransactionMode.DEFERRED.name
                    )
                ),
                true,
                java.lang.Boolean.parseBoolean(
                    pragmaTable.getProperty(
                        SQLiteConfig.Pragma.JDBC_GET_GENERATED_KEYS.pragmaName, "true"
                    )
                )
            )
        }
    }
}
