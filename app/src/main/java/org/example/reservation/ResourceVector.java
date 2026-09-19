package org.example.reservation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Immutable map of resource name to a strictly positive amount.
 * Units are caller-defined integers (millicores, bytes, slot counts, …).
 */
public record ResourceVector(Map<ResourceName, Long> amounts) {

    public ResourceVector {
        Objects.requireNonNull(amounts, "amounts");
        if (amounts.isEmpty()) {
            throw new IllegalArgumentException("resource vector must not be empty");
        }
        for (Map.Entry<ResourceName, Long> entry : amounts.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "resource name");
            Long amount = entry.getValue();
            if (amount == null || amount <= 0) {
                throw new IllegalArgumentException("amount must be positive: " + entry.getKey());
            }
        }
        amounts = Map.copyOf(amounts);
    }

    public static ResourceVector of(ResourceName name, long amount) {
        return new ResourceVector(Map.of(name, amount));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Set<ResourceName> names() {
        return amounts.keySet();
    }

    public OptionalLong amount(ResourceName name) {
        Long value = amounts.get(name);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    public long amountOrZero(ResourceName name) {
        return amounts.getOrDefault(name, 0L);
    }

    public static final class Builder {
        private final Map<ResourceName, Long> amounts = new LinkedHashMap<>();

        public Builder put(ResourceName name, long amount) {
            Objects.requireNonNull(name, "name");
            if (amount <= 0) {
                throw new IllegalArgumentException("amount must be positive: " + name);
            }
            amounts.put(name, amount);
            return this;
        }

        public ResourceVector build() {
            return new ResourceVector(amounts);
        }
    }
}
