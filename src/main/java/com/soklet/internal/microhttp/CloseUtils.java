package com.soklet.internal.microhttp;

import org.jspecify.annotations.NonNull;
import java.io.Closeable;
import java.io.IOException;

class CloseUtils {

    static void closeQuietly(@NonNull Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignore) {
            // suppress
        }
    }
}
