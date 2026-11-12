package com.soklet.internal.microhttp;

import org.jspecify.annotations.NonNull;
public record Header(@NonNull String name, @NonNull String value) {
}
