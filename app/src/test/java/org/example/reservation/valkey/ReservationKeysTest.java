package org.example.reservation.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.example.reservation.Scope;
import org.junit.jupiter.api.Test;

class ReservationKeysTest {

    private static final Scope ACME = Scope.tenant("acme");

    @Test
    void generationSitsOutsideHashTag() {
        ReservationKeys keys = new ReservationKeys();
        assertEquals("{rsv:tenant:acme}:v1:quota", keys.quota(ACME));
        assertEquals("{rsv:tenant:acme}:v1:holds", keys.holds(ACME));
        assertEquals("{rsv:tenant:acme}", keys.tag(ACME));
    }

    @Test
    void namespaceIsInsideTheTag() {
        ReservationKeys keys = new ReservationKeys("prod", 1);
        assertEquals("{rsv:prod:tenant:acme}:v1:quota", keys.quota(ACME));
        assertEquals("{rsv:prod:tenant:acme}:v1:holds", keys.holds(ACME));
    }

    @Test
    void nextGenerationSharesTheTag() {
        ReservationKeys v1 = new ReservationKeys("", 1);
        ReservationKeys v2 = v1.withGeneration(2);
        assertEquals(v1.tag(ACME), v2.tag(ACME));
        assertEquals("{rsv:tenant:acme}:v2:quota", v2.quota(ACME));
        assertEquals("{rsv:tenant:acme}:v2:holds", v2.holds(ACME));
    }

    @Test
    void rejectsNonPositiveGeneration() {
        assertThrows(IllegalArgumentException.class, () -> new ReservationKeys("", 0));
    }

    @Test
    void rejectsHashTagBracesInNamespace() {
        assertThrows(IllegalArgumentException.class, () -> new ReservationKeys("a}b", 1));
    }
}
