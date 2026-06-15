package org.example.sqlite.jdbc.jdbc3

import java.sql.SQLException
import java.sql.Savepoint

class JDBC3Savepoint : Savepoint {
    val id: Int

    val name: String?

    internal constructor(id: Int) {
        this.id = id
        this.name = null
    }

    internal constructor(id: Int, name: String) {
        this.id = id
        this.name = name
    }

    @Throws(SQLException::class)
    override fun getSavepointId(): Int {
        return id
    }

    @Throws(SQLException::class)
    override fun getSavepointName(): String {
        return if (name == null) String.format("SQLITE_SAVEPOINT_%s", id) else name
    }
}
