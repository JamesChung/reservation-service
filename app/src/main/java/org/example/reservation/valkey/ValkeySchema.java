package org.example.reservation.valkey;

/**
 * Occupancy schema for Valkey keys and hold JSON.
 *
 * <p>Key generation is the {@code :vN:} infix <em>outside</em> the hash tag so vN and vN+1
 * stay in one cluster slot and can be {@code RENAME}d atomically. Payload {@code v} is the
 * JSON document version. This release writes generation {@link #CURRENT} keys and
 * {@code "v": CURRENT}.
 *
 * <p>Rolling JARs may share one Valkey only while they use the same key generation.
 * A generation bump is a dedicated migrate ({@link ValkeyReservationStore#migrateScope}).
 * Additive JSON fields do not bump the key generation: old readers ignore unknown
 * properties; {@code extend} preserves extra fields on the decoded table.
 */
final class ValkeySchema {

    static final int CURRENT = 1;
    static final int MIN_READABLE = 1;

    private ValkeySchema() {}

    static void requireGeneration(int generation) {
        if (generation < 1) {
            throw new IllegalArgumentException("schema generation must be >= 1, got " + generation);
        }
    }

    static boolean readablePayload(Integer version) {
        int v = version == null ? CURRENT : version;
        return v >= MIN_READABLE && v <= CURRENT;
    }
}
