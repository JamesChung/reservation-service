package org.example.reservation.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.reservation.Denied;
import org.example.reservation.DenialReason;
import org.example.reservation.NotFound;
import org.example.reservation.Reservation;
import org.example.reservation.ReservationException;
import org.example.reservation.ReservationId;
import org.example.reservation.ReservationStore;
import org.example.reservation.ReservationStoreContract;
import org.example.reservation.ReserveRequest;
import org.example.reservation.ResourceVector;
import org.example.reservation.Scope;
import org.example.reservation.StaleLease;
import org.example.reservation.Try;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ValkeyReservationStoreTest extends ReservationStoreContract {

    @RegisterExtension
    static final AppleContainerValkey VALKEY = new AppleContainerValkey();

    @Override
    protected ReservationStore createStore(Clock clock) {
        ValkeyReservationStore store = VALKEY.store();
        store.flushForTests();
        return store;
    }

    @Override
    protected boolean supportsInjectedClock() {
        return false;
    }

    @Test
    void expiredGenerationCannotKillReusedIdOnServerClock() throws InterruptedException {
        ReservationStore store = createStore(Clock.systemUTC());
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));

        Reservation first = assertGranted(store.tryReserve(new ReserveRequest(
                new ReservationId("run-1"), SCHEDULER_A, acme, ResourceVector.of(RUNS, 1), Duration.ofSeconds(1))));
        TimeUnit.MILLISECONDS.sleep(1300);

        assertInstanceOf(NotFound.class, assertFailure(store.find(acme, first.id())));
        Reservation second = assertGranted(store.tryReserve(new ReserveRequest(
                new ReservationId("run-1"), SCHEDULER_B, acme, ResourceVector.of(RUNS, 1), Duration.ofSeconds(10))));
        assertNotEquals(first.token(), second.token());
        assertEquals(SCHEDULER_B, second.owner());
        assertFalse(assertSuccess(store.release(first)));
        assertInstanceOf(StaleLease.class, assertFailure(store.extend(first, Duration.ofSeconds(10))));
        assertEquals(second.token(), assertSuccess(store.find(acme, second.id())).token());
    }

    @Test
    void concurrentLastSlotIsAtomic() throws Exception {
        ReservationStore store = createStore(Clock.systemUTC());
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Try<Reservation>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int n = i;
                futures.add(executor.submit(() -> store.tryReserve(request(acme, "run-" + n, RUNS, 1))));
            }
            int granted = 0;
            int denied = 0;
            for (Future<Try<Reservation>> future : futures) {
                if (future.get(5, TimeUnit.SECONDS).isSuccess()) {
                    granted++;
                } else {
                    denied++;
                }
            }
            assertEquals(1, granted);
            assertEquals(7, denied);
        }
    }

    @Test
    void newHoldJsonIncludesSchemaVersion() throws Exception {
        ReservationStore store = createStore(Clock.systemUTC());
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        Reservation held = assertGranted(store.tryReserve(request(acme, "run-1", RUNS, 1)));

        String raw = VALKEY.commands().hget(new ReservationKeys().holds(acme), held.id().value());
        JsonNode node = MAPPER.readTree(raw);
        assertEquals(ValkeySchema.CURRENT, node.get("v").asInt());
        assertEquals("{rsv:tenant:acme}:v1:holds", new ReservationKeys().holds(acme));
        assertEquals("{rsv:tenant:acme}:v1:quota", new ReservationKeys().quota(acme));
    }

    @Test
    void extraJsonFieldSurvivesExtend() throws Exception {
        ReservationStore store = createStore(Clock.systemUTC());
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        Reservation held = assertGranted(store.tryReserve(request(acme, "run-1", RUNS, 1)));

        String holdsKey = new ReservationKeys().holds(acme);
        ObjectNode node = (ObjectNode) MAPPER.readTree(VALKEY.commands().hget(holdsKey, held.id().value()));
        node.put("extra", "keep-me");
        VALKEY.commands().hset(holdsKey, held.id().value(), MAPPER.writeValueAsString(node));

        Reservation extended = assertSuccess(store.extend(held, Duration.ofMinutes(10)));
        assertEquals(held.token(), extended.token());
        JsonNode after = MAPPER.readTree(VALKEY.commands().hget(holdsKey, held.id().value()));
        assertEquals("keep-me", after.get("extra").asText());
        assertEquals(ValkeySchema.CURRENT, after.get("v").asInt());
    }

    @Test
    void futurePayloadVersionIsNotSweptOrGranted() throws Exception {
        ReservationStore store = createStore(Clock.systemUTC());
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        Reservation held = assertGranted(store.tryReserve(request(acme, "run-1", RUNS, 1)));

        String holdsKey = new ReservationKeys().holds(acme);
        ObjectNode node = (ObjectNode) MAPPER.readTree(VALKEY.commands().hget(holdsKey, held.id().value()));
        node.put("v", 99);
        VALKEY.commands().hset(holdsKey, held.id().value(), MAPPER.writeValueAsString(node));

        assertTrue(assertSuccess(store.list(acme)).isEmpty());
        assertEquals(1, assertSuccess(store.usage(acme)).of(RUNS).used());
        assertInstanceOf(ReservationException.class, assertFailure(store.find(acme, held.id())));
        assertEquals(99, MAPPER.readTree(VALKEY.commands().hget(holdsKey, held.id().value())).get("v").asInt());

        Denied denied = assertDenied(store.tryReserve(request(acme, "run-2", RUNS, 1)));
        assertEquals(DenialReason.INSUFFICIENT_CAPACITY, denied.reason());
        assertEquals(denied.usage().of(RUNS).used(), assertSuccess(store.usage(acme)).of(RUNS).used());
        assertInstanceOf(ReservationException.class, assertFailure(store.extend(held, Duration.ofMinutes(10))));
        assertInstanceOf(ReservationException.class, assertFailure(store.release(held)));
    }

    @Test
    void expiredFuturePayloadIsNotSwept() throws Exception {
        ReservationStore store = createStore(Clock.systemUTC());
        Scope acme = Scope.tenant("acme");
        assertOk(store.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));
        Reservation held = assertGranted(store.tryReserve(request(acme, "run-1", RUNS, 1)));

        String holdsKey = new ReservationKeys().holds(acme);
        ObjectNode node = (ObjectNode) MAPPER.readTree(VALKEY.commands().hget(holdsKey, held.id().value()));
        node.put("v", 99);
        node.put("expiresAt", 0);
        VALKEY.commands().hset(holdsKey, held.id().value(), MAPPER.writeValueAsString(node));

        assertTrue(assertSuccess(store.list(acme)).isEmpty());
        assertSuccess(store.usage(acme));
        assertEquals(99, MAPPER.readTree(VALKEY.commands().hget(holdsKey, held.id().value())).get("v").asInt());
    }

    @Test
    void migrateScopeRenamesPreviousGeneration() {
        ValkeyReservationStore v1 = VALKEY.store();
        v1.flushForTests();
        Scope acme = Scope.tenant("acme");
        assertOk(v1.replaceQuotas(acme, ResourceVector.of(RUNS, 2)));
        Reservation held = assertGranted(v1.tryReserve(request(acme, "run-1", RUNS, 1)));

        ValkeyReservationStore v2 = new ValkeyReservationStore(VALKEY.commands(), new ReservationKeys("", 2));
        assertInstanceOf(NotFound.class, assertFailure(v2.find(acme, held.id())));

        assertOk(v2.migrateScope(acme));
        Reservation found = assertSuccess(v2.find(acme, held.id()));
        assertEquals(held.token(), found.token());
        assertEquals(1, assertSuccess(v2.usage(acme)).of(RUNS).used());
        assertTrue(assertSuccess(v2.release(held)));
        assertOk(v2.migrateScope(acme));
    }

    @Test
    void migrateScopeFailsWhenBothGenerationsHaveData() {
        ValkeyReservationStore v1 = VALKEY.store();
        v1.flushForTests();
        Scope acme = Scope.tenant("acme");
        assertOk(v1.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));

        ValkeyReservationStore v2 = new ValkeyReservationStore(VALKEY.commands(), new ReservationKeys("", 2));
        assertOk(v2.replaceQuotas(acme, ResourceVector.of(RUNS, 1)));

        assertInstanceOf(ReservationException.class, assertFailure(v2.migrateScope(acme)));
    }

    @Test
    void connectFactoryRoundTrip() {
        try (ValkeyReservationStore store = ValkeyReservationStore.connect(
                ValkeySettings.parse(VALKEY.redisUri()).withTimeout(Duration.ofSeconds(5)))) {
            Scope scope = Scope.tenant("connect-smoke");
            assertOk(store.replaceQuotas(scope, ResourceVector.of(RUNS, 1)));
            assertGranted(store.tryReserve(request(scope, "run-1", RUNS, 1)));
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
}
