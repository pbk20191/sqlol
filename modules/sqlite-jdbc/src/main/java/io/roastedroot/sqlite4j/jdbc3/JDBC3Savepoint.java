package io.roastedroot.sqlite4j.jdbc3;

import java.sql.SQLException;
import java.sql.Savepoint;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class JDBC3Savepoint implements Savepoint {

    final int id;

    final @Nullable String name;

    JDBC3Savepoint(int id) {
        this.id = id;
        this.name = null;
    }

    JDBC3Savepoint(int id, @NonNull String name) {
        this.id = id;
        this.name = name;
    }

    public int getSavepointId() throws SQLException {
        return id;
    }

    public String getSavepointName() throws SQLException {
        return name == null ? String.format("SQLITE_SAVEPOINT_%s", id) : name;
    }
}
