package io.roastedroot.sqlite4j.jdbc4;

import io.roastedroot.sqlite4j.SQLiteConnection;
import io.roastedroot.sqlite4j.jdbc3.JDBC3PreparedStatement;
import java.io.InputStream;
import java.io.Reader;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.util.Arrays;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class JDBC4PreparedStatement extends JDBC3PreparedStatement
        implements PreparedStatement, ParameterMetaData {

    @Override
    public String toString() {
        return sql + " \n parameters=" + Arrays.toString(batch);
    }

    public JDBC4PreparedStatement(@NonNull SQLiteConnection conn, @NonNull String sql) throws SQLException {
        super(conn, sql);
    }

    // JDBC 4
    public void setRowId(int parameterIndex, @Nullable RowId x) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setNString(int parameterIndex, @Nullable String value) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setNCharacterStream(int parameterIndex, @Nullable Reader value, long length)
            throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setNClob(int parameterIndex, @Nullable NClob value) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setClob(int parameterIndex, @Nullable Reader reader, long length) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setBlob(int parameterIndex, @Nullable InputStream inputStream, long length)
            throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setNClob(int parameterIndex, @Nullable Reader reader, long length) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setSQLXML(int parameterIndex, @Nullable SQLXML xmlObject) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setAsciiStream(int parameterIndex, @Nullable InputStream x, long length) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setBinaryStream(int parameterIndex, @Nullable InputStream x, long length)
            throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setCharacterStream(int parameterIndex, @Nullable Reader reader, long length)
            throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setAsciiStream(int parameterIndex, @Nullable InputStream x) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setBinaryStream(int parameterIndex, @Nullable InputStream x) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setCharacterStream(int parameterIndex, @Nullable Reader reader) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setNCharacterStream(int parameterIndex, @Nullable Reader value) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setClob(int parameterIndex, @Nullable Reader reader) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setBlob(int parameterIndex, @Nullable InputStream inputStream) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public void setNClob(int parameterIndex, @Nullable Reader reader) throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }
}
