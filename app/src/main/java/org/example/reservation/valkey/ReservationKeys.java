package org.example.reservation.valkey;

import org.example.reservation.Scope;

/**
 * Per-scope key names. Generation sits <em>outside</em> the hash tag so a later
 * {@code :v2:} pair hashes to the same slot as {@code :v1:} and Lua can migrate
 * with {@code RENAME}.
 */
final class ReservationKeys {

    private final String namespace;
    private final int generation;

    ReservationKeys() {
        this("", ValkeySchema.CURRENT);
    }

    ReservationKeys(String namespace, int generation) {
        ValkeySchema.requireGeneration(generation);
        this.namespace = namespace == null ? "" : namespace;
        if (this.namespace.contains("{") || this.namespace.contains("}")) {
            throw new IllegalArgumentException("namespace must not contain '{' or '}'");
        }
        this.generation = generation;
    }

    int generation() {
        return generation;
    }

    String namespace() {
        return namespace;
    }

    ReservationKeys withGeneration(int generation) {
        return new ReservationKeys(namespace, generation);
    }

    String tag(Scope scope) {
        if (namespace.isEmpty()) {
            return "{rsv:" + scope.type() + ":" + scope.id() + "}";
        }
        return "{rsv:" + namespace + ":" + scope.type() + ":" + scope.id() + "}";
    }

    String quota(Scope scope) {
        return tag(scope) + ":v" + generation + ":quota";
    }

    String holds(Scope scope) {
        return tag(scope) + ":v" + generation + ":holds";
    }
}
