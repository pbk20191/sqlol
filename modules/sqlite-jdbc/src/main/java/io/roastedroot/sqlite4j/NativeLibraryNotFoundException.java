package io.roastedroot.sqlite4j;

import org.jspecify.annotations.NonNull;

public class NativeLibraryNotFoundException extends Exception {
    public NativeLibraryNotFoundException(@NonNull String message) {
        super(message);
    }
}
