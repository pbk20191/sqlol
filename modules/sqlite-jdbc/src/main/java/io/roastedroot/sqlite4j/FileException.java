package io.roastedroot.sqlite4j;

import org.jspecify.annotations.NonNull;

public class FileException extends Exception {
    public FileException(@NonNull String message) {
        super(message);
    }
}
