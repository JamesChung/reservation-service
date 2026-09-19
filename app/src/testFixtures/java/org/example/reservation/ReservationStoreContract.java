package org.example.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

public abstract class ReservationStoreContract {

    protected static final ResourceName RUNS = new ResourceName("pipeline.runs");
    protected static final ResourceName CPU = new ResourceName("cpu.millis");
    protected static final ResourceName MEM = new ResourceName("memory.bytes");
    protected static final Owner SCHEDULER_A = new Owner("scheduler-a");
    protected static final Owner SCHEDULER_B = new Owner("scheduler-b");

    protected abstract ReservationStore createStore(Clock clock);

    /** Valkey uses server time; clock-injection tests are skipped there. */
    protected boolean supportsInjectedClock() {
        return true;
    }

    private ReservationStore store() {
        return createStore(Clock.systemUTC());
    }

    @Test
    void sixthPipelineDenied() throws Exception {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 5)));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Try<Reservation>>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int n = i;
                futures.add(executor.submit(() -> store.tryReserve(request(acme, "run-" + n, RUNS, 1))));
            }
            int granted = 0;
            int denied = 0;
            for (Future<Try<Reservation>> future : futures) {
                switch (future.get(5, TimeUnit.SECONDS)) {
                    case Try.Success<Reservation> success -> granted++;
                    case Try.Failure<Reservation> failure -> {
                        Denied deniedResult = assertInstanceOf(Denied.class, failure.cause());
                        assertEquals(DenialReason.INSUFFICIENT_CAPACITY, deniedResult.reason());
                        denied++;
                    }
                }
            }
            assertEquals(5, granted);
            assertEquals(1, denied);
            assertEquals(5, assertSuccess(store.usage(acme)).of(RUNS).used());
        }
    }

    @Test
    void atomicMultiResourceDoesNotPartiallyConsume() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(
                acme, ResourceVector.builder().put(RUNS, 2).put(CPU, 10).put(MEM, 10).build()));

        Reservation first = assertGranted(store.tryReserve(multi(acme, "run-1", 1, 8, 1)));

        Denied denied = assertDenied(store.tryReserve(multi(acme, "run-2", 1, 5, 1)));
        assertEquals(DenialReason.INSUFFICIENT_CAPACITY, denied.reason());
        assertEquals(CPU, denied.shortages().getFirst().name());

        assertEquals(1, assertSuccess(store.usage(acme)).of(RUNS).used());
        assertEquals(8, assertSuccess(store.usage(acme)).of(CPU).used());
        assertTrue(assertSuccess(store.release(first)));

        assertGranted(store.tryReserve(multi(acme, "run-2", 1, 5, 1)));
        assertEquals(5, assertSuccess(store.usage(acme)).of(CPU).used());
    }

    @Test
    void idempotentRetryKeepsTokenOwnerAndExpiry() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        ReserveRequest request = request(acme, "run-1", RUNS, 1);

        Reservation first = assertGranted(store.tryReserve(request));
        Reservation second = assertGranted(store.tryReserve(request));
        assertEquals(first, second);
        assertEquals(first.token(), second.token());
        assertEquals(first.owner(), second.owner());
        assertEquals(first.expiresAt(), second.expiresAt());
        assertEquals(1, assertSuccess(store.usage(acme)).of(RUNS).used());
    }

    @Test
    void sameIdDifferentOwnerReturnsOriginalOwner() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        Reservation first = assertGranted(store.tryReserve(request(acme, SCHEDULER_A, "run-1", RUNS, 1)));
        Reservation second = assertGranted(store.tryReserve(request(acme, SCHEDULER_B, "run-1", RUNS, 1)));
        assertEquals(first.token(), second.token());
        assertEquals(SCHEDULER_A, second.owner());
    }

    @Test
    void sameIdDifferentScopeIsAllowed() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        Scope globex = Scope.tenant("globex");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        assertOk(store.replaceQuotas(globex, ResourceVector.of(RUNS, 1)));
        Reservation acmeHold = assertGranted(store.tryReserve(request(acme, "run-1", RUNS, 1)));
        Reservation globexHold = assertGranted(store.tryReserve(request(globex, "run-1", RUNS, 1)));
        assertEquals(acmeHold.id(), globexHold.id());
        assertNotEquals(acmeHold.scope(), globexHold.scope());
        assertNotEquals(acmeHold.token(), globexHold.token());
    }

    @Test
    void sameIdDifferentVectorIsConflict() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.builder().put(RUNS, 5).put(CPU, 10).build()));
        Reservation first = assertGranted(store.tryReserve(request(acme, "run-1", RUNS, 1)));

        ReservationConflict conflict =
                assertInstanceOf(ReservationConflict.class, assertFailure(store.tryReserve(multi(acme, "run-1", 1, 1, 0))));
        assertEquals(first.id(), conflict.existing().id());
        assertEquals(1, assertSuccess(store.usage(acme)).of(RUNS).used());
    }

    @Test
    void releaseIsFencedAndIdempotent() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        Reservation held = assertGranted(store.tryReserve(request(acme, "run-1", RUNS, 1)));

        assertTrue(assertSuccess(store.release(held)));
        assertFalse(assertSuccess(store.release(held)));
        assertInstanceOf(NotFound.class, assertFailure(store.find(acme, held.id())));
        assertGranted(store.tryReserve(request(acme, "run-2", RUNS, 1)));
    }

    @Test
    void wrongTokenReleaseDoesNotDropHold() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        Reservation held = assertGranted(store.tryReserve(request(acme, "run-1", RUNS, 1)));
        Reservation forged = new Reservation(
                held.id(),
                new LeaseToken("other-generation"),
                held.owner(),
                held.scope(),
                held.resources(),
                held.createdAt(),
                held.expiresAt());

        assertFalse(assertSuccess(store.release(forged)));
        assertEquals(held.token(), assertSuccess(store.find(acme, held.id())).token());
        assertEquals(1, assertSuccess(store.usage(acme)).of(RUNS).used());
    }

    @Test
    void expiredGenerationCannotKillReusedId() {
        Assumptions.assumeTrue(supportsInjectedClock());
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ReservationStore store = createStore(clock);
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));

        Reservation first = assertGranted(store.tryReserve(new ReserveRequest(
                new ReservationId("run-1"), SCHEDULER_A, acme, ResourceVector.of(RUNS, 1), Duration.ofSeconds(10))));
        clock.advance(Duration.ofSeconds(11));

        Reservation second = assertGranted(store.tryReserve(new ReserveRequest(
                new ReservationId("run-1"), SCHEDULER_B, acme, ResourceVector.of(RUNS, 1), Duration.ofSeconds(10))));
        assertNotEquals(first.token(), second.token());
        assertEquals(SCHEDULER_B, second.owner());

        assertFalse(assertSuccess(store.release(first)));
        assertInstanceOf(StaleLease.class, assertFailure(store.extend(first, Duration.ofSeconds(10))));
        assertEquals(second.token(), assertSuccess(store.find(acme, second.id())).token());
        assertEquals(1, assertSuccess(store.usage(acme)).of(RUNS).used());
    }

    @Test
    void extendWrongTokenIsStaleLease() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        Reservation held = assertGranted(store.tryReserve(request(acme, "run-1", RUNS, 1)));
        Reservation forged = new Reservation(
                held.id(),
                new LeaseToken("other-generation"),
                held.owner(),
                held.scope(),
                held.resources(),
                held.createdAt(),
                held.expiresAt());
        assertInstanceOf(StaleLease.class, assertFailure(store.extend(forged, Duration.ofSeconds(30))));
    }

    @Test
    void extendKeepsTokenAndRefreshesExpiry() {
        Assumptions.assumeTrue(supportsInjectedClock());
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ReservationStore store = createStore(clock);
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        Reservation held = assertGranted(store.tryReserve(new ReserveRequest(
                new ReservationId("run-1"), SCHEDULER_A, acme, ResourceVector.of(RUNS, 1), Duration.ofSeconds(10))));

        clock.advance(Duration.ofSeconds(8));
        Reservation extended = assertSuccess(store.extend(held, Duration.ofSeconds(10)));
        assertEquals(held.token(), extended.token());
        clock.advance(Duration.ofSeconds(8));
        assertEquals(held.token(), assertSuccess(store.find(acme, held.id())).token());
        clock.advance(Duration.ofSeconds(3));
        assertInstanceOf(NotFound.class, assertFailure(store.find(acme, held.id())));
    }

    @Test
    void noQuotaConfiguredIsHardDeny() {
        ReservationStore store = store();
        Denied denied = assertDenied(store.tryReserve(request(Scope.tenant("acme"), "run-1", RUNS, 1)));
        assertEquals(DenialReason.NO_QUOTA_CONFIGURED, denied.reason());
    }

    @Test
    void requestExceedingQuotaIsHardDeny() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 5)));
        Denied denied = assertDenied(store.tryReserve(request(acme, "run-1", RUNS, 10)));
        assertEquals(DenialReason.REQUEST_EXCEEDS_QUOTA, denied.reason());
    }

    @Test
    void missingQuotaDimensionIsHardDeny() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 5)));
        Denied denied = assertDenied(store.tryReserve(multi(acme, "run-1", 1, 1, 0)));
        assertEquals(DenialReason.NO_QUOTA_CONFIGURED, denied.reason());
        assertEquals(0, assertSuccess(store.usage(acme)).of(RUNS).used());
    }

    @Test
    void loweringQuotaDoesNotRevokeHolds() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 5)));
        assertGranted(store.tryReserve(request(acme, "run-1", RUNS, 1)));
        assertGranted(store.tryReserve(request(acme, "run-2", RUNS, 1)));
        assertGranted(store.tryReserve(request(acme, "run-3", RUNS, 1)));

        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        assertEquals(3, assertSuccess(store.list(acme)).size());
        Denied denied = assertDenied(store.tryReserve(request(acme, "run-4", RUNS, 1)));
        assertEquals(DenialReason.INSUFFICIENT_CAPACITY, denied.reason());
        assertEquals(3, assertSuccess(store.usage(acme)).of(RUNS).used());
        assertEquals(1, assertSuccess(store.usage(acme)).of(RUNS).limit());
    }

    @Test
    void deleteQuotasLeavesHoldsAndBlocksNewAdmits() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 5)));
        Reservation held = assertGranted(store.tryReserve(request(acme, "run-1", RUNS, 1)));
        assertOk(store.deleteQuotas(acme));
        assertInstanceOf(NotFound.class, assertFailure(store.quotas(acme)));
        assertEquals(held.token(), assertSuccess(store.find(acme, held.id())).token());
        Denied denied = assertDenied(store.tryReserve(request(acme, "run-2", RUNS, 1)));
        assertEquals(DenialReason.NO_QUOTA_CONFIGURED, denied.reason());
    }

    @Test
    void tenantsAreIsolated() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        Scope globex = Scope.tenant("globex");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        assertOk(store.replaceQuotas(globex, ResourceVector.of(RUNS, 1)));
        assertGranted(store.tryReserve(request(acme, "acme-1", RUNS, 1)));
        assertGranted(store.tryReserve(request(globex, "globex-1", RUNS, 1)));
        assertDenied(store.tryReserve(request(acme, "acme-2", RUNS, 1)));
    }

    @Test
    void quotasRoundTrip() {
        ReservationStore store = store();
        Scope acme = Scope.tenant("acme");
        assertInstanceOf(NotFound.class, assertFailure(store.quotas(acme)));
        ResourceVector quotas = ResourceVector.builder().put(RUNS, 5).put(CPU, 16_000).build();
        assertOk(store.replaceQuotas(acme, quotas));
        assertEquals(quotas, assertSuccess(store.quotas(acme)));
    }

    protected static Reservation assertGranted(Try<Reservation> result) {
        return assertSuccess(result);
    }

    protected static Denied assertDenied(Try<Reservation> result) {
        return assertInstanceOf(Denied.class, assertFailure(result));
    }

    protected static void assertOk(Try<Void> result) {
        if (result.isFailure()) {
            fail("expected ok but was " + result);
        }
    }

    protected static <T> T assertSuccess(Try<T> result) {
        if (result instanceof Try.Success<T> success) {
            return success.value();
        }
        fail("expected success but was " + result);
        return null;
    }

    protected static Throwable assertFailure(Try<?> result) {
        if (result instanceof Try.Failure<?> failure) {
            return failure.cause();
        }
        fail("expected failure but was " + result);
        return null;
    }

    protected static ReserveRequest request(Scope scope, String id, ResourceName name, long amount) {
        return request(scope, SCHEDULER_A, id, name, amount);
    }

    protected static ReserveRequest request(Scope scope, Owner owner, String id, ResourceName name, long amount) {
        return new ReserveRequest(
                new ReservationId(id), owner, scope, ResourceVector.of(name, amount), Duration.ofMinutes(10));
    }

    private static ReserveRequest multi(Scope scope, String id, long runs, long cpu, long mem) {
        ResourceVector.Builder builder = ResourceVector.builder().put(RUNS, runs).put(CPU, cpu);
        if (mem > 0) {
            builder.put(MEM, mem);
        }
        return new ReserveRequest(new ReservationId(id), SCHEDULER_A, scope, builder.build(), Duration.ofMinutes(10));
    }
}
