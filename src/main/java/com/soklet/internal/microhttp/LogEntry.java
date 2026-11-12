package com.soklet.internal.microhttp;

import org.jspecify.annotations.NonNull;
public record LogEntry(@NonNull String key, @NonNull String value) {
}
