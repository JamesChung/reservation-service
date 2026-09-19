package org.example.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.example.reservation.memory.InMemoryReservationStore;
import org.junit.jupiter.api.Test;

class ReservationRetriesTest {

    private static final ResourceName RUNS = new ResourceName("pipeline.runs");
    private static final Owner OWNER = new Owner("scheduler-a");

    @Test
    void zeroTimeoutIsSingleTry() {
        ReservationStore store = new InMemoryReservationStore();
        Scope acme = Scope.tenant("acme");
        store.replaceQuotas(acme, ResourceVector.of(RUNS, 1));
        store.tryReserve(request(acme, "run-0"));

        Try<Reservation> result =
                retries().reserve(store, request(acme, "run-1"), Duration.ZERO);
        Denied denied = assertInstanceOf(Denied.class, cause(result));
        assertEquals(DenialReason.INSUFFICIENT_CAPACITY, denied.reason());
    }

    @Test
    void hardDenyDoesNotWait() {
        ReservationStore store = new InMemoryReservationStore();
        long started = System.nanoTime();
        Try<Reservation> result =
                retries().reserve(store, request(Scope.tenant("acme"), "run-1"), Duration.ofSeconds(5));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        Denied denied = assertInstanceOf(Denied.class, cause(result));
        assertEquals(DenialReason.NO_QUOTA_CONFIGURED, denied.reason());
        assertTrue(elapsedMs < 1_000, "hard deny waited: " + elapsedMs + "ms");
    }

    @Test
    void requestExceedingQuotaDoesNotWait() {
        ReservationStore store = new InMemoryReservationStore();
        Scope acme = Scope.tenant("acme");
        store.replaceQuotas(acme, ResourceVector.of(RUNS, 5));
        long started = System.nanoTime();
        Try<Reservation> result = retries().reserve(
                store,
                new ReserveRequest(
                        new ReservationId("run-1"),
                        OWNER,
                        acme,
                        ResourceVector.of(RUNS, 10),
                        Duration.ofMinutes(10)),
                Duration.ofSeconds(5));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        Denied denied = assertInstanceOf(Denied.class, cause(result));
        assertEquals(DenialReason.REQUEST_EXCEEDS_QUOTA, denied.reason());
        assertTrue(elapsedMs < 1_000, "hard deny waited: " + elapsedMs + "ms");
    }

    @Test
    void waitsUntilRelease() throws Exception {
        ReservationStore store = new InMemoryReservationStore();
        Scope acme = Scope.tenant("acme");
        store.replaceQuotas(acme, ResourceVector.of(RUNS, 1));
        Reservation held = success(store.tryReserve(request(acme, "run-0")));

        AtomicReference<Try<Reservation>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = Thread.ofPlatform().start(() -> {
            result.set(retries(Duration.ofMillis(20)).reserve(store, request(acme, "run-1"), Duration.ofSeconds(2)));
            done.countDown();
        });
        waitUntilAliveAndLikelySleeping(waiter);
        assertTrue(booleanSuccess(store.release(held)));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        Reservation granted = success(result.get());
        assertEquals("run-1", granted.id().value());
    }

    @Test
    void timesOutWhileStillFull() {
        ReservationStore store = new InMemoryReservationStore();
        Scope acme = Scope.tenant("acme");
        store.replaceQuotas(acme, ResourceVector.of(RUNS, 1));
        store.tryReserve(request(acme, "run-0"));

        Try<Reservation> result =
                retries(Duration.ofMillis(20)).reserve(store, request(acme, "run-1"), Duration.ofMillis(80));
        assertInstanceOf(TimedOut.class, cause(result));
        assertInstanceOf(NotFound.class, cause(store.find(acme, new ReservationId("run-1"))));
    }

    @Test
    void interruptDuringWait() throws Exception {
        ReservationStore store = new InMemoryReservationStore();
        Scope acme = Scope.tenant("acme");
        store.replaceQuotas(acme, ResourceVector.of(RUNS, 1));
        store.tryReserve(request(acme, "run-0"));

        AtomicReference<Try<Reservation>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = Thread.ofPlatform().start(() -> {
            result.set(retries(Duration.ofSeconds(1)).reserve(store, request(acme, "run-1"), Duration.ofSeconds(5)));
            done.countDown();
        });
        waitUntilAliveAndLikelySleeping(waiter);
        waiter.interrupt();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertInstanceOf(InterruptedException.class, cause(result.get()));
        assertInstanceOf(NotFound.class, cause(store.find(acme, new ReservationId("run-1"))));
    }

    @Test
    void negativeTimeoutRejected() {
        ReservationStore store = new InMemoryReservationStore();
        assertThrows(
                IllegalArgumentException.class,
                () -> retries().reserve(store, request(Scope.tenant("acme"), "run-1"), Duration.ofSeconds(-1)));
    }

    @Test
    void pollsUntilCapacity() {
        AtomicInteger attempts = new AtomicInteger();
        ReservationStore store = new InMemoryReservationStore();
        Scope acme = Scope.tenant("acme");
        store.replaceQuotas(acme, ResourceVector.of(RUNS, 1));
        Reservation held = success(store.tryReserve(request(acme, "run-0")));

        Sleeper sleeper = duration -> {
            if (attempts.incrementAndGet() == 2) {
                store.release(held);
            }
        };
        Try<Reservation> result = new ReservationRetries(java.time.Clock.systemUTC(), sleeper, Duration.ofMillis(10))
                .reserve(store, request(acme, "run-1"), Duration.ofSeconds(2));
        assertEquals("run-1", success(result).id().value());
    }

    private static ReservationRetries retries() {
        return retries(LeasePolicy.DEFAULT_POLL_INTERVAL);
    }

    private static ReservationRetries retries(Duration poll) {
        return new ReservationRetries(java.time.Clock.systemUTC(), Sleeper.threadSleep(), poll);
    }

    private static ReserveRequest request(Scope scope, String id) {
        return new ReserveRequest(
                new ReservationId(id), OWNER, scope, ResourceVector.of(RUNS, 1), Duration.ofMinutes(10));
    }

    private static Reservation success(Try<Reservation> result) {
        if (result instanceof Try.Success<Reservation> granted) {
            return granted.value();
        }
        fail("expected success but was " + result);
        return null;
    }

    private static boolean booleanSuccess(Try<Boolean> result) {
        if (result instanceof Try.Success<Boolean> ok) {
            return ok.value();
        }
        fail("expected success but was " + result);
        return false;
    }

    private static Throwable cause(Try<?> result) {
        if (result instanceof Try.Failure<?> failure) {
            return failure.cause();
        }
        fail("expected failure but was " + result);
        return null;
    }

    private static void waitUntilAliveAndLikelySleeping(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.TIMED_WAITING && thread.getState() != Thread.State.WAITING) {
            if (!thread.isAlive()) {
                fail("thread exited before waiting: " + thread.getState());
            }
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for sleep, state=" + thread.getState());
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }
}
