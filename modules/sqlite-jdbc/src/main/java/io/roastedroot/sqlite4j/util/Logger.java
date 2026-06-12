package io.roastedroot.sqlite4j.util;

import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;

/** A simple internal Logger interface. */
public interface Logger {
    void trace(@NonNull Supplier<String> message);

    void info(@NonNull Supplier<String> message);

    void warn(@NonNull Supplier<String> message);

    void error(@NonNull Supplier<String> message, @NonNull Throwable t);
}
