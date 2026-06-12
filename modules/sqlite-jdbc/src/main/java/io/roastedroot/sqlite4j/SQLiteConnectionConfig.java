package io.roastedroot.sqlite4j;

import static io.roastedroot.sqlite4j.SQLiteConfig.DEFAULT_DATE_STRING_FORMAT;

import io.roastedroot.sqlite4j.date.FastDateFormat;
import java.sql.Connection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Connection local configurations */
public class SQLiteConnectionConfig implements Cloneable {
    private SQLiteConfig.@NonNull DateClass dateClass = SQLiteConfig.DateClass.INTEGER;
    private SQLiteConfig.@NonNull DatePrecision datePrecision =
            SQLiteConfig.DatePrecision.MILLISECONDS; // Calendar.SECOND or Calendar.MILLISECOND
    private @NonNull String dateStringFormat = DEFAULT_DATE_STRING_FORMAT;
    private @NonNull FastDateFormat dateFormat = FastDateFormat.getInstance(dateStringFormat);

    private int transactionIsolation = Connection.TRANSACTION_SERIALIZABLE;
    private SQLiteConfig.@NonNull TransactionMode transactionMode =
            SQLiteConfig.TransactionMode.DEFERRED;
    private boolean autoCommit = true;
    private boolean getGeneratedKeys = true;

    public static SQLiteConnectionConfig fromPragmaTable(@NonNull Properties pragmaTable) {
        return new SQLiteConnectionConfig(
                SQLiteConfig.DateClass.getDateClass(
                        pragmaTable.getProperty(
                                SQLiteConfig.Pragma.DATE_CLASS.pragmaName,
                                SQLiteConfig.DateClass.INTEGER.name())),
                SQLiteConfig.DatePrecision.getPrecision(
                        pragmaTable.getProperty(
                                SQLiteConfig.Pragma.DATE_PRECISION.pragmaName,
                                SQLiteConfig.DatePrecision.MILLISECONDS.name())),
                pragmaTable.getProperty(
                        SQLiteConfig.Pragma.DATE_STRING_FORMAT.pragmaName,
                        DEFAULT_DATE_STRING_FORMAT),
                Connection.TRANSACTION_SERIALIZABLE,
                SQLiteConfig.TransactionMode.getMode(
                        pragmaTable.getProperty(
                                SQLiteConfig.Pragma.TRANSACTION_MODE.pragmaName,
                                SQLiteConfig.TransactionMode.DEFERRED.name())),
                true,
                Boolean.parseBoolean(
                        pragmaTable.getProperty(
                                SQLiteConfig.Pragma.JDBC_GET_GENERATED_KEYS.pragmaName, "true")));
    }

    public SQLiteConnectionConfig(
            SQLiteConfig.@NonNull DateClass dateClass,
            SQLiteConfig.@NonNull DatePrecision datePrecision,
            @NonNull String dateStringFormat,
            int transactionIsolation,
            SQLiteConfig.@NonNull TransactionMode transactionMode,
            boolean autoCommit,
            boolean getGeneratedKeys) {
        setDateClass(dateClass);
        setDatePrecision(datePrecision);
        setDateStringFormat(dateStringFormat);
        setTransactionIsolation(transactionIsolation);
        setTransactionMode(transactionMode);
        setAutoCommit(autoCommit);
        setGetGeneratedKeys(getGeneratedKeys);
    }

    public SQLiteConnectionConfig copyConfig() {
        return new SQLiteConnectionConfig(
                dateClass,
                datePrecision,
                dateStringFormat,
                transactionIsolation,
                transactionMode,
                autoCommit,
                getGeneratedKeys);
    }

    public long getDateMultiplier() {
        return (datePrecision == SQLiteConfig.DatePrecision.MILLISECONDS) ? 1L : 1000L;
    }

    public SQLiteConfig.DateClass getDateClass() {
        return dateClass;
    }

    public void setDateClass(SQLiteConfig.@NonNull DateClass dateClass) {
        this.dateClass = dateClass;
    }

    public SQLiteConfig.DatePrecision getDatePrecision() {
        return datePrecision;
    }

    public void setDatePrecision(SQLiteConfig.@NonNull DatePrecision datePrecision) {
        this.datePrecision = datePrecision;
    }

    public String getDateStringFormat() {
        return dateStringFormat;
    }

    public void setDateStringFormat(@NonNull String dateStringFormat) {
        this.dateStringFormat = dateStringFormat;
        this.dateFormat = FastDateFormat.getInstance(dateStringFormat);
    }

    public FastDateFormat getDateFormat() {
        return dateFormat;
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    public int getTransactionIsolation() {
        return transactionIsolation;
    }

    public void setTransactionIsolation(int transactionIsolation) {
        this.transactionIsolation = transactionIsolation;
    }

    public SQLiteConfig.TransactionMode getTransactionMode() {
        return transactionMode;
    }

    @SuppressWarnings("deprecation")
    public void setTransactionMode(SQLiteConfig.@NonNull TransactionMode transactionMode) {
        this.transactionMode = transactionMode;
    }

    public boolean isGetGeneratedKeys() {
        return getGeneratedKeys;
    }

    public void setGetGeneratedKeys(boolean getGeneratedKeys) {
        this.getGeneratedKeys = getGeneratedKeys;
    }

    private static final Map<SQLiteConfig.TransactionMode, String> beginCommandMap =
            new EnumMap<>(SQLiteConfig.TransactionMode.class);

    static {
        beginCommandMap.put(SQLiteConfig.TransactionMode.DEFERRED, "begin;");
        beginCommandMap.put(SQLiteConfig.TransactionMode.IMMEDIATE, "begin immediate;");
        beginCommandMap.put(SQLiteConfig.TransactionMode.EXCLUSIVE, "begin exclusive;");
    }

    @Nullable String transactionPrefix() {
        return beginCommandMap.get(transactionMode);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof SQLiteConnectionConfig)) return false;
        SQLiteConnectionConfig that = (SQLiteConnectionConfig) o;
        return transactionIsolation == that.transactionIsolation
                && autoCommit == that.autoCommit
                && getGeneratedKeys == that.getGeneratedKeys
                && dateClass == that.dateClass
                && datePrecision == that.datePrecision
                && Objects.equals(dateStringFormat, that.dateStringFormat)
                && Objects.equals(dateFormat, that.dateFormat)
                && transactionMode == that.transactionMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                dateClass,
                datePrecision,
                dateStringFormat,
                dateFormat,
                transactionIsolation,
                transactionMode,
                autoCommit,
                getGeneratedKeys);
    }
}
