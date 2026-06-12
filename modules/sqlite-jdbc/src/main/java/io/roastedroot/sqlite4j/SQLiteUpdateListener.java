package io.roastedroot.sqlite4j;

import org.jspecify.annotations.NonNull;

/** https://www.sqlite.org/c3ref/update_hook.html */
public interface SQLiteUpdateListener {

    public enum Type {
        INSERT,
        DELETE,
        UPDATE
    }

    void onUpdate(@NonNull Type type, @NonNull String database, @NonNull String table, long rowId);
}
