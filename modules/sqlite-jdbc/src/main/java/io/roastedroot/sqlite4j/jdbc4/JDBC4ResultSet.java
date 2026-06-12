package io.roastedroot.sqlite4j.jdbc4;

import io.roastedroot.sqlite4j.core.CoreStatement;
import io.roastedroot.sqlite4j.jdbc3.JDBC3ResultSet;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class JDBC4ResultSet extends JDBC3ResultSet implements ResultSet, ResultSetMetaData {

    public JDBC4ResultSet(@NonNull CoreStatement stmt) {
        super(stmt);
    }

    @Override
    public void close() throws SQLException {
        final boolean wasOpen = isOpen(); // prevent close() recursion
        super.close();
        // close-on-completion regardless of closeStmt
        if (wasOpen && stmt instanceof JDBC4Statement) {
            JDBC4Statement stat = (JDBC4Statement) stmt;
            // check if its not closed already in which case no-op
            if (stat.closeOnCompletion && !stat.isClosed()) {
                stat.close();
            }
        }
    }

    // JDBC 4
    public <T> T unwrap(@NonNull Class<T> iface) throws ClassCastException {
        return iface.cast(this);
    }

    public boolean isWrapperFor(@NonNull Class<?> iface) {
        return iface.isInstance(this);
    }

    public RowId getRowId(int columnIndex) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public RowId getRowId(@NonNull String columnLabel) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateRowId(int columnIndex, @NonNull RowId x) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateRowId(@NonNull String columnLabel, @NonNull RowId x) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public int getHoldability() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    public boolean isClosed() throws SQLException {
        return !isOpen();
    }

    public void updateNString(int columnIndex, @NonNull String nString) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateNString(@NonNull String columnLabel, @NonNull String nString) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateNClob(int columnIndex, @NonNull NClob nClob) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateNClob(@NonNull String columnLabel, @NonNull NClob nClob) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public NClob getNClob(int columnIndex) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public NClob getNClob(@NonNull String columnLabel) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public SQLXML getSQLXML(@NonNull String columnLabel) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateSQLXML(int columnIndex, @NonNull SQLXML xmlObject) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateSQLXML(@NonNull String columnLabel, @NonNull SQLXML xmlObject) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public String getNString(int columnIndex) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public String getNString(@NonNull String columnLabel) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public @Nullable Reader getNCharacterStream(int col) throws SQLException {
        String data = getString(col);
        return getNCharacterStreamInternal(data);
    }

    private @Nullable Reader getNCharacterStreamInternal(@Nullable String data) {
        if (data == null) {
            return null;
        }
        Reader reader = new StringReader(data);
        return reader;
    }

    public @Nullable Reader getNCharacterStream(@NonNull String col) throws SQLException {
        String data = getString(col);
        return getNCharacterStreamInternal(data);
    }

    public void updateNCharacterStream(int columnIndex, @NonNull Reader x, long length) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateNCharacterStream(@NonNull String columnLabel, @NonNull Reader reader, long length)
            throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateAsciiStream(int columnIndex, @NonNull InputStream x, long length) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateBinaryStream(int columnIndex, @NonNull InputStream x, long length)
            throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateCharacterStream(int columnIndex, @NonNull Reader x, long length) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateAsciiStream(@NonNull String columnLabel, @NonNull InputStream x, long length)
            throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateBinaryStream(@NonNull String columnLabel, @NonNull InputStream x, long length)
            throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateCharacterStream(@NonNull String columnLabel, @NonNull Reader reader, long length)
            throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateBlob(int columnIndex, @NonNull InputStream inputStream, long length)
            throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateBlob(@NonNull String columnLabel, @NonNull InputStream inputStream, long length)
            throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateClob(int columnIndex, @NonNull Reader reader, long length) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateClob(@NonNull String columnLabel, @NonNull Reader reader, long length) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateNClob(int columnIndex, @NonNull Reader reader, long length) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateNClob(@NonNull String columnLabel, @NonNull Reader reader, long length) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateNCharacterStream(int columnIndex, @NonNull Reader x) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateNCharacterStream(@NonNull String columnLabel, @NonNull Reader reader) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateAsciiStream(int columnIndex, @NonNull InputStream x) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateBinaryStream(int columnIndex, @NonNull InputStream x) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateCharacterStream(int columnIndex, @NonNull Reader x) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateAsciiStream(@NonNull String columnLabel, @NonNull InputStream x) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateBinaryStream(@NonNull String columnLabel, @NonNull InputStream x) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateCharacterStream(@NonNull String columnLabel, @NonNull Reader reader) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateBlob(int columnIndex, @NonNull InputStream inputStream) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateBlob(@NonNull String columnLabel, @NonNull InputStream inputStream) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateClob(int columnIndex, @NonNull Reader reader) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateClob(@NonNull String columnLabel, @NonNull Reader reader) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateNClob(int columnIndex, @NonNull Reader reader) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void updateNClob(@NonNull String columnLabel, @NonNull Reader reader) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public <T> @Nullable T getObject(int columnIndex, @NonNull Class<T> type) throws SQLException {
        if (type == null) throw new SQLException("requested type cannot be null");
        if (type == String.class) return type.cast(getString(columnIndex));
        if (type == Boolean.class) return type.cast(getBoolean(columnIndex));
        if (type == BigDecimal.class) return type.cast(getBigDecimal(columnIndex));
        if (type == byte[].class) return type.cast(getBytes(columnIndex));
        if (type == Date.class) return type.cast(getDate(columnIndex));
        if (type == Time.class) return type.cast(getTime(columnIndex));
        if (type == Timestamp.class) return type.cast(getTimestamp(columnIndex));
        if (type == LocalDate.class) {
            try {
                Date date = getDate(columnIndex);
                if (date != null) return type.cast(date.toLocalDate());
                else return null;
            } catch (SQLException sqlException) {
                // If the FastDateParser failed, try parse it with LocalDate.
                // It's a workaround for a value like '2022-12-1' (i.e no time presents).
                return type.cast(LocalDate.parse(getString(columnIndex)));
            }
        }
        if (type == LocalTime.class) {
            try {
                Time time = getTime(columnIndex);
                if (time != null) return type.cast(time.toLocalTime());
                else return null;
            } catch (SQLException sqlException) {
                // If the FastDateParser failed, try parse it with LocalTime.
                // It's a workaround for a value like '11:22:22' (i.e no date presents).
                return type.cast(LocalTime.parse(getString(columnIndex)));
            }
        }
        if (type == LocalDateTime.class) {
            try {
                Timestamp timestamp = getTimestamp(columnIndex);
                if (timestamp != null) return type.cast(timestamp.toLocalDateTime());
                else return null;
            } catch (SQLException e) {
                // If the FastDateParser failed, try parse it with LocalDateTime.
                return type.cast(LocalDateTime.parse(getString(columnIndex)));
            }
        }

        int columnType = safeGetColumnType(markCol(columnIndex));
        if (type == Double.class) {
            if (columnType == SQLITE_INTEGER || columnType == SQLITE_FLOAT)
                return type.cast(getDouble(columnIndex));
            throw new SQLException("Bad value for type Double");
        }
        if (type == Long.class) {
            if (columnType == SQLITE_INTEGER || columnType == SQLITE_FLOAT)
                return type.cast(getLong(columnIndex));
            throw new SQLException("Bad value for type Long");
        }
        if (type == Float.class) {
            if (columnType == SQLITE_INTEGER || columnType == SQLITE_FLOAT)
                return type.cast(getFloat(columnIndex));
            throw new SQLException("Bad value for type Float");
        }
        if (type == Integer.class) {
            if (columnType == SQLITE_INTEGER || columnType == SQLITE_FLOAT)
                return type.cast(getInt(columnIndex));
            throw new SQLException("Bad value for type Integer");
        }

        throw unsupported();
    }

    public <T> @Nullable T getObject(@NonNull String columnLabel, @NonNull Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    protected SQLException unsupported() {
        return new SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver");
    }

    // ResultSet ////////////////////////////////////////////////////

    public Array getArray(int i) throws SQLException {
        throw unsupported();
    }

    public Array getArray(@NonNull String col) throws SQLException {
        throw unsupported();
    }

    public @Nullable InputStream getAsciiStream(int col) throws SQLException {
        String data = getString(col);
        return getAsciiStreamInternal(data);
    }

    public @Nullable InputStream getAsciiStream(@NonNull String col) throws SQLException {
        String data = getString(col);
        return getAsciiStreamInternal(data);
    }

    private @Nullable InputStream getAsciiStreamInternal(@Nullable String data) {
        if (data == null) {
            return null;
        }
        InputStream inputStream;
        try {
            inputStream = new ByteArrayInputStream(data.getBytes("ASCII"));
        } catch (UnsupportedEncodingException e) {
            return null;
        }
        return inputStream;
    }

    @Deprecated
    public BigDecimal getBigDecimal(int col, int s) throws SQLException {
        throw unsupported();
    }

    @Deprecated
    public BigDecimal getBigDecimal(@NonNull String col, int s) throws SQLException {
        throw unsupported();
    }

    public Blob getBlob(int col) throws SQLException {
        throw unsupported();
    }

    public Blob getBlob(@NonNull String col) throws SQLException {
        throw unsupported();
    }

    public @Nullable Clob getClob(int col) throws SQLException {
        String clob = getString(col);
        return clob == null ? null : new SqliteClob(clob);
    }

    public @Nullable Clob getClob(@NonNull String col) throws SQLException {
        String clob = getString(col);
        return clob == null ? null : new SqliteClob(clob);
    }

    @SuppressWarnings("rawtypes")
    public Object getObject(int col, @NonNull Map map) throws SQLException {
        throw unsupported();
    }

    @SuppressWarnings("rawtypes")
    public Object getObject(@NonNull String col, @NonNull Map map) throws SQLException {
        throw unsupported();
    }

    public Ref getRef(int i) throws SQLException {
        throw unsupported();
    }

    public Ref getRef(@NonNull String col) throws SQLException {
        throw unsupported();
    }

    public @Nullable InputStream getUnicodeStream(int col) throws SQLException {
        return getAsciiStream(col);
    }

    public @Nullable InputStream getUnicodeStream(@NonNull String col) throws SQLException {
        return getAsciiStream(col);
    }

    public URL getURL(int col) throws SQLException {
        throw unsupported();
    }

    public URL getURL(@NonNull String col) throws SQLException {
        throw unsupported();
    }

    public void insertRow() throws SQLException {
        throw new SQLException("ResultSet is TYPE_FORWARD_ONLY");
    }

    public void moveToCurrentRow() throws SQLException {
        throw new SQLException("ResultSet is TYPE_FORWARD_ONLY");
    }

    public void moveToInsertRow() throws SQLException {
        throw new SQLException("ResultSet is TYPE_FORWARD_ONLY");
    }

    public boolean last() throws SQLException {
        throw new SQLException("ResultSet is TYPE_FORWARD_ONLY");
    }

    public boolean previous() throws SQLException {
        throw new SQLException("ResultSet is TYPE_FORWARD_ONLY");
    }

    public boolean relative(int rows) throws SQLException {
        throw new SQLException("ResultSet is TYPE_FORWARD_ONLY");
    }

    public boolean absolute(int row) throws SQLException {
        throw new SQLException("ResultSet is TYPE_FORWARD_ONLY");
    }

    public void afterLast() throws SQLException {
        throw new SQLException("ResultSet is TYPE_FORWARD_ONLY");
    }

    public void beforeFirst() throws SQLException {
        throw new SQLException("ResultSet is TYPE_FORWARD_ONLY");
    }

    public boolean first() throws SQLException {
        throw new SQLException("ResultSet is TYPE_FORWARD_ONLY");
    }

    public void cancelRowUpdates() throws SQLException {
        throw unsupported();
    }

    public void deleteRow() throws SQLException {
        throw unsupported();
    }

    public void updateArray(int col, @NonNull Array x) throws SQLException {
        throw unsupported();
    }

    public void updateArray(@NonNull String col, @NonNull Array x) throws SQLException {
        throw unsupported();
    }

    public void updateAsciiStream(int col, @NonNull InputStream x, int l) throws SQLException {
        throw unsupported();
    }

    public void updateAsciiStream(@NonNull String col, @NonNull InputStream x, int l) throws SQLException {
        throw unsupported();
    }

    public void updateBigDecimal(int col, @NonNull BigDecimal x) throws SQLException {
        throw unsupported();
    }

    public void updateBigDecimal(@NonNull String col, @NonNull BigDecimal x) throws SQLException {
        throw unsupported();
    }

    public void updateBinaryStream(int c, @NonNull InputStream x, int l) throws SQLException {
        throw unsupported();
    }

    public void updateBinaryStream(@NonNull String c, @NonNull InputStream x, int l) throws SQLException {
        throw unsupported();
    }

    public void updateBlob(int col, @NonNull Blob x) throws SQLException {
        throw unsupported();
    }

    public void updateBlob(@NonNull String col, @NonNull Blob x) throws SQLException {
        throw unsupported();
    }

    public void updateBoolean(int col, boolean x) throws SQLException {
        throw unsupported();
    }

    public void updateBoolean(@NonNull String col, boolean x) throws SQLException {
        throw unsupported();
    }

    public void updateByte(int col, byte x) throws SQLException {
        throw unsupported();
    }

    public void updateByte(@NonNull String col, byte x) throws SQLException {
        throw unsupported();
    }

    public void updateBytes(int col, byte @NonNull [] x) throws SQLException {
        throw unsupported();
    }

    public void updateBytes(@NonNull String col, byte @NonNull [] x) throws SQLException {
        throw unsupported();
    }

    public void updateCharacterStream(int c, @NonNull Reader x, int l) throws SQLException {
        throw unsupported();
    }

    public void updateCharacterStream(@NonNull String c, @NonNull Reader r, int l) throws SQLException {
        throw unsupported();
    }

    public void updateClob(int col, @NonNull Clob x) throws SQLException {
        throw unsupported();
    }

    public void updateClob(@NonNull String col, @NonNull Clob x) throws SQLException {
        throw unsupported();
    }

    public void updateDate(int col, @NonNull Date x) throws SQLException {
        throw unsupported();
    }

    public void updateDate(@NonNull String col, @NonNull Date x) throws SQLException {
        throw unsupported();
    }

    public void updateDouble(int col, double x) throws SQLException {
        throw unsupported();
    }

    public void updateDouble(@NonNull String col, double x) throws SQLException {
        throw unsupported();
    }

    public void updateFloat(int col, float x) throws SQLException {
        throw unsupported();
    }

    public void updateFloat(@NonNull String col, float x) throws SQLException {
        throw unsupported();
    }

    public void updateInt(int col, int x) throws SQLException {
        throw unsupported();
    }

    public void updateInt(@NonNull String col, int x) throws SQLException {
        throw unsupported();
    }

    public void updateLong(int col, long x) throws SQLException {
        throw unsupported();
    }

    public void updateLong(@NonNull String col, long x) throws SQLException {
        throw unsupported();
    }

    public void updateNull(int col) throws SQLException {
        throw unsupported();
    }

    public void updateNull(@NonNull String col) throws SQLException {
        throw unsupported();
    }

    public void updateObject(int c, @NonNull Object x) throws SQLException {
        throw unsupported();
    }

    public void updateObject(int c, @NonNull Object x, int s) throws SQLException {
        throw unsupported();
    }

    public void updateObject(@NonNull String col, @NonNull Object x) throws SQLException {
        throw unsupported();
    }

    public void updateObject(@NonNull String c, @NonNull Object x, int s) throws SQLException {
        throw unsupported();
    }

    public void updateRef(int col, @NonNull Ref x) throws SQLException {
        throw unsupported();
    }

    public void updateRef(@NonNull String c, @NonNull Ref x) throws SQLException {
        throw unsupported();
    }

    public void updateRow() throws SQLException {
        throw unsupported();
    }

    public void updateShort(int c, short x) throws SQLException {
        throw unsupported();
    }

    public void updateShort(@NonNull String c, short x) throws SQLException {
        throw unsupported();
    }

    public void updateString(int c, @NonNull String x) throws SQLException {
        throw unsupported();
    }

    public void updateString(@NonNull String c, @NonNull String x) throws SQLException {
        throw unsupported();
    }

    public void updateTime(int c, @NonNull Time x) throws SQLException {
        throw unsupported();
    }

    public void updateTime(@NonNull String c, @NonNull Time x) throws SQLException {
        throw unsupported();
    }

    public void updateTimestamp(int c, @NonNull Timestamp x) throws SQLException {
        throw unsupported();
    }

    public void updateTimestamp(@NonNull String c, @NonNull Timestamp x) throws SQLException {
        throw unsupported();
    }

    public void refreshRow() throws SQLException {
        throw unsupported();
    }

    class SqliteClob implements NClob {

        private @Nullable String data;

        protected SqliteClob(@NonNull String data) {
            this.data = data;
        }

        public void free() throws SQLException {
            data = null;
        }

        public @Nullable InputStream getAsciiStream() throws SQLException {
            return getAsciiStreamInternal(data);
        }

        public @Nullable Reader getCharacterStream() throws SQLException {
            return getNCharacterStreamInternal(data);
        }

        public @Nullable Reader getCharacterStream(long arg0, long arg1) throws SQLException {
            return getNCharacterStreamInternal(data);
        }

        public String getSubString(long position, int length) throws SQLException {
            if (data == null) {
                throw new SQLException("no data");
            }
            if (position < 1) {
                throw new SQLException("Position must be greater than or equal to 1");
            }
            if (length < 0) {
                throw new SQLException("Length must be greater than or equal to 0");
            }
            int start = (int) position - 1;
            return data.substring(start, Math.min(start + length, data.length()));
        }

        public long length() throws SQLException {
            if (data == null) {
                throw new SQLException("no data");
            }
            return data.length();
        }

        public long position(@NonNull String arg0, long arg1) throws SQLException {
            unsupported();
            return -1;
        }

        public long position(@NonNull Clob arg0, long arg1) throws SQLException {
            unsupported();
            return -1;
        }

        public @Nullable OutputStream setAsciiStream(long arg0) throws SQLException {
            unsupported();
            return null;
        }

        public @Nullable Writer setCharacterStream(long arg0) throws SQLException {
            unsupported();
            return null;
        }

        public int setString(long arg0, @NonNull String arg1) throws SQLException {
            unsupported();
            return -1;
        }

        public int setString(long arg0, @NonNull String arg1, int arg2, int arg3) throws SQLException {
            unsupported();
            return -1;
        }

        public void truncate(long arg0) throws SQLException {
            unsupported();
        }
    }
}
