/*--------------------------------------------------------------------------
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *--------------------------------------------------------------------------*/
package io.roastedroot.sqlite4j.javax;

import io.roastedroot.sqlite4j.SQLiteConnection;
import io.roastedroot.sqlite4j.core.DB;
import io.roastedroot.sqlite4j.jdbc4.JDBC4PooledConnection;
import io.roastedroot.sqlite4j.jdbc4.JDBC4PreparedStatement;
import io.roastedroot.sqlite4j.jdbc4.JDBC4Statement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class SQLitePooledConnection extends JDBC4PooledConnection {

    protected @Nullable SQLiteConnection physicalConn;
    protected volatile @Nullable Connection handleConn;

    protected @NonNull List<ConnectionEventListener> listeners = new ArrayList<ConnectionEventListener>();

    /**
     * Constructor.
     *
     * @param physicalConn The physical Connection.
     */
    protected SQLitePooledConnection(@NonNull SQLiteConnection physicalConn) {
        this.physicalConn = physicalConn;
    }

    public @Nullable SQLiteConnection getPhysicalConn() {
        return physicalConn;
    }

    /**
     * @see javax.sql.PooledConnection#close()
     */
    public void close() throws SQLException {
        if (handleConn != null) {
            listeners.clear();
            handleConn.close();
        }

        if (physicalConn != null) {
            try {
                physicalConn.close();
            } finally {
                physicalConn = null;
            }
        }
    }

    /**
     * @see javax.sql.PooledConnection#getConnection()
     */
    public Connection getConnection() throws SQLException {
        if (handleConn != null) handleConn.close();

        handleConn =
                (Connection)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {Connection.class},
                                new InvocationHandler() {
                                    boolean isClosed;

                                    public Object invoke(@NonNull Object proxy, @NonNull Method method, Object @NonNull [] args)
                                            throws Throwable {
                                        try {
                                            String name = method.getName();
                                            if ("close".equals(name)) {
                                                ConnectionEvent event =
                                                        new ConnectionEvent(
                                                                SQLitePooledConnection.this);

                                                for (int i = listeners.size() - 1; i >= 0; i--) {
                                                    listeners.get(i).connectionClosed(event);
                                                }

                                                if (!physicalConn.getAutoCommit()) {
                                                    physicalConn.rollback();
                                                }
                                                physicalConn.setAutoCommit(true);
                                                isClosed = true;

                                                return null; // don't close physical connection
                                            } else if ("isClosed".equals(name)) {
                                                if (!isClosed)
                                                    isClosed =
                                                            ((Boolean)
                                                                            method.invoke(
                                                                                    physicalConn,
                                                                                    args))
                                                                    .booleanValue();

                                                return isClosed;
                                            }

                                            if (isClosed) {
                                                throw new SQLException("Connection is closed");
                                            }

                                            return method.invoke(physicalConn, args);
                                        } catch (SQLException e) {
                                            if ("database connection closed"
                                                    .equals(e.getMessage())) {
                                                ConnectionEvent event =
                                                        new ConnectionEvent(
                                                                SQLitePooledConnection.this, e);

                                                for (int i = listeners.size() - 1; i >= 0; i--) {
                                                    listeners.get(i).connectionErrorOccurred(event);
                                                }
                                            }

                                            throw e;
                                        } catch (InvocationTargetException ex) {
                                            throw ex.getCause();
                                        }
                                    }
                                });

        return handleConn;
    }

    /**
     * @see javax.sql.PooledConnection#addConnectionEventListener(javax.sql.ConnectionEventListener)
     */
    public void addConnectionEventListener(@NonNull ConnectionEventListener listener) {
        listeners.add(listener);
    }

    /**
     * @see
     *     javax.sql.PooledConnection#removeConnectionEventListener(javax.sql.ConnectionEventListener)
     */
    public void removeConnectionEventListener(@NonNull ConnectionEventListener listener) {
        listeners.remove(listener);
    }

    public List<ConnectionEventListener> getListeners() {
        return listeners;
    }
}

class SQLitePooledConnectionHandle extends SQLiteConnection {
    private final @NonNull SQLitePooledConnection parent;
    private final @NonNull AtomicBoolean isClosed = new AtomicBoolean(false);

    public SQLitePooledConnectionHandle(@NonNull SQLitePooledConnection parent) {
        super(parent.getPhysicalConn().getDatabase());
        this.parent = parent;
    }

    @Override
    public Statement createStatement() throws SQLException {
        return new JDBC4Statement(this);
    }

    @Override
    public PreparedStatement prepareStatement(@NonNull String sql) throws SQLException {
        return new JDBC4PreparedStatement(this, sql);
    }

    @Override
    public @Nullable CallableStatement prepareCall(@NonNull String sql) throws SQLException {
        return null;
    }

    @Override
    public @Nullable String nativeSQL(@NonNull String sql) throws SQLException {
        return null;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {}

    @Override
    public boolean getAutoCommit() throws SQLException {
        return false;
    }

    @Override
    public void commit() throws SQLException {}

    @Override
    public void rollback() throws SQLException {}

    @Override
    public void close() throws SQLException {
        ConnectionEvent event = new ConnectionEvent(parent);

        List<ConnectionEventListener> listeners = parent.getListeners();
        for (int i = listeners.size() - 1; i >= 0; i--) {
            listeners.get(i).connectionClosed(event);
        }

        if (!parent.getPhysicalConn().getAutoCommit()) {
            parent.getPhysicalConn().rollback();
        }
        parent.getPhysicalConn().setAutoCommit(true);
        isClosed.set(true);
    }

    @Override
    public boolean isClosed() {
        return isClosed.get();
    }

    @Override
    public @Nullable DatabaseMetaData getMetaData() throws SQLException {
        return null;
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {}

    @Override
    public boolean isReadOnly() throws SQLException {
        return false;
    }

    @Override
    public void setCatalog(@NonNull String catalog) throws SQLException {}

    @Override
    public @Nullable String getCatalog() throws SQLException {
        return null;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {}

    @Override
    public int getTransactionIsolation() {
        return 0;
    }

    @Override
    public @Nullable SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {}

    @Override
    public @Nullable Statement createStatement(int resultSetType, int resultSetConcurrency)
            throws SQLException {
        return null;
    }

    @Override
    public @Nullable PreparedStatement prepareStatement(
            @NonNull String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return null;
    }

    @Override
    public @Nullable CallableStatement prepareCall(@NonNull String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        return null;
    }

    @Override
    public @Nullable Map<String, Class<?>> getTypeMap() throws SQLException {
        return null;
    }

    @Override
    public void setTypeMap(@NonNull Map<String, Class<?>> map) throws SQLException {}

    @Override
    public void setHoldability(int holdability) throws SQLException {}

    @Override
    public int getHoldability() throws SQLException {
        return 0;
    }

    @Override
    public @Nullable Savepoint setSavepoint() throws SQLException {
        return null;
    }

    @Override
    public @Nullable Savepoint setSavepoint(@NonNull String name) throws SQLException {
        return null;
    }

    @Override
    public void rollback(@NonNull Savepoint savepoint) throws SQLException {}

    @Override
    public void releaseSavepoint(@NonNull Savepoint savepoint) throws SQLException {}

    @Override
    public @Nullable Statement createStatement(
            int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        return null;
    }

    @Override
    public @Nullable PreparedStatement prepareStatement(
            @NonNull String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        return null;
    }

    @Override
    public @Nullable CallableStatement prepareCall(
            @NonNull String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        return null;
    }

    @Override
    public @Nullable PreparedStatement prepareStatement(@NonNull String sql, int autoGeneratedKeys)
            throws SQLException {
        return null;
    }

    @Override
    public @Nullable PreparedStatement prepareStatement(@NonNull String sql, int @NonNull [] columnIndexes) throws SQLException {
        return null;
    }

    @Override
    public @Nullable PreparedStatement prepareStatement(@NonNull String sql, String @NonNull [] columnNames)
            throws SQLException {
        return null;
    }

    @Override
    public @Nullable Clob createClob() throws SQLException {
        return null;
    }

    @Override
    public @Nullable Blob createBlob() throws SQLException {
        return null;
    }

    @Override
    public @Nullable NClob createNClob() throws SQLException {
        return null;
    }

    @Override
    public @Nullable SQLXML createSQLXML() throws SQLException {
        return null;
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        return false;
    }

    @Override
    public void setClientInfo(@NonNull String name, @NonNull String value) throws SQLClientInfoException {}

    @Override
    public void setClientInfo(@NonNull Properties properties) throws SQLClientInfoException {}

    @Override
    public @Nullable String getClientInfo(@NonNull String name) throws SQLException {
        return null;
    }

    @Override
    public @Nullable Properties getClientInfo() throws SQLException {
        return null;
    }

    @Override
    public @Nullable Array createArrayOf(@NonNull String typeName, Object @NonNull [] elements) throws SQLException {
        return null;
    }

    @Override
    public @Nullable Struct createStruct(@NonNull String typeName, Object @NonNull [] attributes) throws SQLException {
        return null;
    }

    @Override
    public void setSchema(@NonNull String schema) throws SQLException {}

    @Override
    public @Nullable String getSchema() throws SQLException {
        return null;
    }

    @Override
    public void abort(@NonNull Executor executor) throws SQLException {}

    @Override
    public void setNetworkTimeout(@NonNull Executor executor, int milliseconds) throws SQLException {}

    @Override
    public int getNetworkTimeout() throws SQLException {
        return 0;
    }

    @Override
    public <T> @Nullable T unwrap(@NonNull Class<T> iface) throws SQLException {
        return null;
    }

    @Override
    public boolean isWrapperFor(@NonNull Class<?> iface) throws SQLException {
        return false;
    }

    @Override
    public int getBusyTimeout() {
        return 0;
    }

    @Override
    public void setBusyTimeout(int timeoutMillis) {}

    @Override
    public @Nullable DB getDatabase() {
        return null;
    }
}
