package io.roastedroot.sqlite4j.jdbc4;

import javax.sql.PooledConnection;
import javax.sql.StatementEventListener;
import org.jspecify.annotations.NonNull;

public abstract class JDBC4PooledConnection implements PooledConnection {

    public void addStatementEventListener(@NonNull StatementEventListener listener) {
        // TODO impl
    }

    public void removeStatementEventListener(@NonNull StatementEventListener listener) {
        // TODO impl
    }
}
