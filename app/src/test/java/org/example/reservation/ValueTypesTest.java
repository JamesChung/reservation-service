package org.example.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValueTypesTest {

    private static final ResourceName RUNS = new ResourceName("pipeline.runs");

    @Test
    void resourceNameRejectsIllegalCharacters() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceName("cpu millis"));
        assertThrows(IllegalArgumentException.class, () -> new ResourceName(""));
        assertThrows(NullPointerException.class, () -> new ResourceName(null));
    }

    @Test
    void resourceNameAcceptsDotSeparated() {
        assertEquals("pipeline.runs", new ResourceName("pipeline.runs").value());
    }

    @Test
    void scopeRequiresNonBlankParts() {
        assertThrows(IllegalArgumentException.class, () -> new Scope("tenant", " "));
        assertEquals(new Scope("tenant", "acme"), Scope.tenant("acme"));
    }

    @Test
    void reservationIdRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new ReservationId(" "));
    }

    @Test
    void resourceVectorRejectsEmptyAndNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceVector(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ResourceVector.of(RUNS, 0));
        assertThrows(IllegalArgumentException.class, () -> ResourceVector.builder().build());
        assertThrows(IllegalArgumentException.class, () -> ResourceVector.builder().put(RUNS, -1));
    }

    @Test
    void resourceVectorBuilderOverwritesName() {
        ResourceVector vector = ResourceVector.builder().put(RUNS, 1).put(RUNS, 5).build();
        assertEquals(5, vector.amountOrZero(RUNS));
        assertTrue(vector.amount(RUNS).isPresent());
    }

    @Test
    void ownerAndTokenRejectBlank() {
        assertThrows(IllegalArgumentException.class, () -> new Owner(" "));
        assertThrows(IllegalArgumentException.class, () -> new LeaseToken(" "));
    }

    @Test
    void reserveRequestEnforcesTtlBoundsAndOwner() {
        var id = new ReservationId("run-1");
        var owner = new Owner("scheduler-a");
        var scope = Scope.tenant("acme");
        var resources = ResourceVector.of(RUNS, 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReserveRequest(id, owner, scope, resources, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReserveRequest(id, owner, scope, resources, Duration.ofMillis(999)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReserveRequest(id, owner, scope, resources, Duration.ofHours(25)));
        assertThrows(
                NullPointerException.class,
                () -> new ReserveRequest(id, null, scope, resources, Duration.ofSeconds(10)));
    }
}
