package io.roastedroot.sqlite4j.jdbc4;

import io.roastedroot.sqlite4j.SQLiteConnection;
import io.roastedroot.sqlite4j.jdbc3.JDBC3DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class JDBC4DatabaseMetaData extends JDBC3DatabaseMetaData {
    public JDBC4DatabaseMetaData(@NonNull SQLiteConnection conn) {
        super(conn);
    }

    // JDBC 4
    public <T> T unwrap(@NonNull Class<T> iface) throws ClassCastException {
        return iface.cast(this);
    }

    public boolean isWrapperFor(@NonNull Class<?> iface) {
        return iface.isInstance(this);
    }

    public RowIdLifetime getRowIdLifetime() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    public ResultSet getSchemas(@Nullable String catalog, @Nullable String schemaPattern)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    public ResultSet getClientInfoProperties() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    public ResultSet getFunctions(
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nullable String functionNamePattern)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    public ResultSet getPseudoColumns(
            @Nullable String catalog,
            @Nullable String schemaPattern,
            @Nullable String tableNamePattern,
            @Nullable String columnNamePattern)
            throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    public boolean generatedKeyAlwaysReturned() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }
}
