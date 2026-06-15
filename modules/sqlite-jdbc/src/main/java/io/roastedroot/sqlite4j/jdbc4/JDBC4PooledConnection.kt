package io.roastedroot.sqlite4j.jdbc4

import javax.sql.PooledConnection
import javax.sql.StatementEventListener

abstract class JDBC4PooledConnection : PooledConnection {
    override fun addStatementEventListener(listener: StatementEventListener) {
        // TODO impl
    }

    override fun removeStatementEventListener(listener: StatementEventListener) {
        // TODO impl
    }
}
