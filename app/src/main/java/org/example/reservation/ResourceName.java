package org.example.reservation;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Opaque resource dimension. Callers own the vocabulary
 * ({@code pipeline.runs}, {@code cpu.millis}, {@code memory.bytes}).
 *
 * Restricted to {@code [A-Za-z0-9._-]+} so names are safe as store keys.
 */
public record ResourceName(String value) {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]+");

    public ResourceName {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid resource name: " + value);
        }
    }
}
