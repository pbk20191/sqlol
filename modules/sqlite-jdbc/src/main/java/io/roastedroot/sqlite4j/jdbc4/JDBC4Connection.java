package io.roastedroot.sqlite4j.jdbc4;

import io.roastedroot.sqlite4j.jdbc3.JDBC3Connection;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.util.Properties;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class JDBC4Connection extends JDBC3Connection {

    public JDBC4Connection(@NonNull String url, @NonNull String fileName, @NonNull Properties prop) throws SQLException {
        super(url, fileName, prop);
    }

    public Statement createStatement(int rst, int rsc, int rsh) throws SQLException {
        checkOpen();
        checkCursor(rst, rsc, rsh);

        return new JDBC4Statement(this);
    }

    public PreparedStatement prepareStatement(@NonNull String sql, int rst, int rsc, int rsh)
            throws SQLException {
        checkOpen();
        checkCursor(rst, rsc, rsh);

        return new JDBC4PreparedStatement(this, sql);
    }

    // JDBC 4
    /**
     * @see java.sql.Connection#isClosed()
     */
    public boolean isClosed() throws SQLException {
        return super.isClosed();
    }

    public <T> T unwrap(@NonNull Class<T> iface) throws ClassCastException {
        // caller should invoke isWrapperFor prior to unwrap
        return iface.cast(this);
    }

    public boolean isWrapperFor(@NonNull Class<?> iface) {
        return iface.isInstance(this);
    }

    public Clob createClob() throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public Blob createBlob() throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public NClob createNClob() throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public SQLXML createSQLXML() throws SQLException {
        // TODO Support this
        throw new SQLFeatureNotSupportedException();
    }

    public boolean isValid(int timeout) throws SQLException {
        if (isClosed()) {
            return false;
        }
        Statement statement = createStatement();
        try {
            return statement.execute("select 1");
        } finally {
            statement.close();
        }
    }

    public void setClientInfo(@NonNull String name, @Nullable String value) throws SQLClientInfoException {
        // TODO Auto-generated method stub

    }

    public void setClientInfo(@NonNull Properties properties) throws SQLClientInfoException {
        // TODO Auto-generated method stub

    }

    public @Nullable String getClientInfo(@NonNull String name) throws SQLException {
        // TODO Auto-generated method stub
        return null;
    }

    public @Nullable Properties getClientInfo() throws SQLException {
        // TODO Auto-generated method stub
        return null;
    }

    public @Nullable Array createArrayOf(@NonNull String typeName, @Nullable Object[] elements) throws SQLException {
        // TODO Auto-generated method stub
        return null;
    }
}
