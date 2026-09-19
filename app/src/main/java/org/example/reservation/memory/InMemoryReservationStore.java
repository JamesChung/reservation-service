package org.example.reservation.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.example.reservation.Denied;
import org.example.reservation.DenialReason;
import org.example.reservation.LeasePolicy;
import org.example.reservation.LeaseToken;
import org.example.reservation.NotFound;
import org.example.reservation.Reservation;
import org.example.reservation.ReservationConflict;
import org.example.reservation.ReservationId;
import org.example.reservation.ReservationStore;
import org.example.reservation.ReserveRequest;
import org.example.reservation.ResourceName;
import org.example.reservation.ResourceShortage;
import org.example.reservation.ResourceUsage;
import org.example.reservation.ResourceVector;
import org.example.reservation.Scope;
import org.example.reservation.StaleLease;
import org.example.reservation.Try;
import org.example.reservation.UsageSnapshot;

/**
 * In-process {@link ReservationStore}. A single fair lock linearizes all operations.
 * Test double / executable spec — not a production limiter (process-wide lock, lazy
 * expiry, wall clock). A Valkey implementation would use per-scope Lua instead.
 */
public final class InMemoryReservationStore implements ReservationStore {

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Map<ReservationId, Reservation> byId = new HashMap<>();
    private final Map<Scope, ScopeState> scopes = new HashMap<>();

    public InMemoryReservationStore() {
        this(Clock.systemUTC());
    }

    public InMemoryReservationStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Try<Void> replaceQuotas(Scope scope, ResourceVector quotas) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(quotas, "quotas");
        lock.lock();
        try {
            state(scope).quotas = quotas;
            return Try.ok();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Try<Void> deleteQuotas(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        lock.lock();
        try {
            ScopeState state = scopes.get(scope);
            if (state != null) {
                state.quotas = null;
            }
            return Try.ok();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Try<ResourceVector> quotas(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        lock.lock();
        try {
            ScopeState state = scopes.get(scope);
            if (state == null || state.quotas == null) {
                return Try.failure(new NotFound("no quota for " + scope));
            }
            return Try.success(state.quotas);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Try<Reservation> tryReserve(ReserveRequest request) {
        Objects.requireNonNull(request, "request");
        lock.lock();
        try {
            sweep(request.scope());
            Reservation existing = live(request.id());
            if (existing != null) {
                if (existing.scope().equals(request.scope())
                        && existing.resources().equals(request.resources())) {
                    return Try.success(existing);
                }
                return Try.failure(new ReservationConflict(existing));
            }
            ScopeState state = state(request.scope());
            Optional<Denied> rejection = rejection(state, request.resources());
            if (rejection.isPresent()) {
                return Try.failure(rejection.get());
            }
            return Try.success(applyGrant(state, request));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Try<Boolean> release(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        lock.lock();
        try {
            sweep(reservation.scope());
            ScopeState state = scopes.get(reservation.scope());
            if (state == null) {
                return Try.success(false);
            }
            Reservation current = state.held.get(reservation.id());
            if (current == null || !current.token().equals(reservation.token())) {
                return Try.success(false);
            }
            removeHeld(state, current);
            return Try.success(true);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Try<Reservation> extend(Reservation reservation, Duration ttl) {
        Objects.requireNonNull(reservation, "reservation");
        LeasePolicy.requireTtl(ttl);
        lock.lock();
        try {
            sweep(reservation.scope());
            ScopeState state = scopes.get(reservation.scope());
            Reservation current = state == null ? null : state.held.get(reservation.id());
            if (current == null || !current.token().equals(reservation.token())) {
                return Try.failure(new StaleLease(reservation.id()));
            }
            Reservation updated = new Reservation(
                    current.id(),
                    current.token(),
                    current.owner(),
                    current.scope(),
                    current.resources(),
                    current.createdAt(),
                    clock.instant().plus(ttl));
            state.held.put(current.id(), updated);
            byId.put(current.id(), updated);
            return Try.success(updated);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Try<Reservation> find(Scope scope, ReservationId id) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(id, "id");
        lock.lock();
        try {
            sweep(scope);
            ScopeState state = scopes.get(scope);
            Reservation current = state == null ? null : state.held.get(id);
            if (current == null) {
                return Try.failure(new NotFound("no reservation " + id.value() + " in " + scope));
            }
            return Try.success(current);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Try<UsageSnapshot> usage(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        lock.lock();
        try {
            sweep(scope);
            return Try.success(snapshot(state(scope)));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Try<List<Reservation>> list(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        lock.lock();
        try {
            sweep(scope);
            ScopeState state = scopes.get(scope);
            if (state == null || state.held.isEmpty()) {
                return Try.success(List.of());
            }
            return Try.success(List.copyOf(state.held.values()));
        } finally {
            lock.unlock();
        }
    }

    private Reservation live(ReservationId id) {
        Reservation existing = byId.get(id);
        if (existing == null) {
            return null;
        }
        sweep(existing.scope());
        return byId.get(id);
    }

    private void sweep(Scope scope) {
        ScopeState state = scopes.get(scope);
        if (state == null) {
            return;
        }
        Instant now = clock.instant();
        List<Reservation> expired = new ArrayList<>();
        for (Reservation reservation : state.held.values()) {
            if (!reservation.expiresAt().isAfter(now)) {
                expired.add(reservation);
            }
        }
        for (Reservation reservation : expired) {
            removeHeld(state, reservation);
        }
    }

    private Reservation applyGrant(ScopeState state, ReserveRequest request) {
        Instant now = clock.instant();
        Reservation reservation = new Reservation(
                request.id(),
                new LeaseToken(UUID.randomUUID().toString()),
                request.owner(),
                request.scope(),
                request.resources(),
                now,
                now.plus(request.ttl()));
        state.held.put(reservation.id(), reservation);
        byId.put(reservation.id(), reservation);
        return reservation;
    }

    private void removeHeld(ScopeState state, Reservation reservation) {
        state.held.remove(reservation.id(), reservation);
        byId.remove(reservation.id(), reservation);
    }

    private Optional<Denied> rejection(ScopeState state, ResourceVector demand) {
        Map<ResourceName, Long> used = used(state);
        if (state.quotas == null) {
            return Optional.of(deny(state, DenialReason.NO_QUOTA_CONFIGURED, missingShortages(demand, used, null)));
        }
        List<ResourceShortage> missing = missingShortages(demand, used, state.quotas);
        if (!missing.isEmpty()) {
            return Optional.of(deny(state, DenialReason.NO_QUOTA_CONFIGURED, missing));
        }
        List<ResourceShortage> exceeds = new ArrayList<>();
        for (Map.Entry<ResourceName, Long> entry : demand.amounts().entrySet()) {
            long limit = state.quotas.amountOrZero(entry.getKey());
            long requested = entry.getValue();
            if (requested > limit) {
                exceeds.add(new ResourceShortage(
                        entry.getKey(), requested, used.getOrDefault(entry.getKey(), 0L), limit));
            }
        }
        if (!exceeds.isEmpty()) {
            return Optional.of(deny(state, DenialReason.REQUEST_EXCEEDS_QUOTA, exceeds));
        }
        List<ResourceShortage> capacity = new ArrayList<>();
        for (Map.Entry<ResourceName, Long> entry : demand.amounts().entrySet()) {
            long limit = state.quotas.amountOrZero(entry.getKey());
            long occupancy = used.getOrDefault(entry.getKey(), 0L);
            long requested = entry.getValue();
            if (occupancy > limit - requested) {
                capacity.add(new ResourceShortage(entry.getKey(), requested, occupancy, limit));
            }
        }
        if (!capacity.isEmpty()) {
            return Optional.of(deny(state, DenialReason.INSUFFICIENT_CAPACITY, capacity));
        }
        return Optional.empty();
    }

    private static List<ResourceShortage> missingShortages(
            ResourceVector demand, Map<ResourceName, Long> used, ResourceVector quotas) {
        List<ResourceShortage> missing = new ArrayList<>();
        for (Map.Entry<ResourceName, Long> entry : demand.amounts().entrySet()) {
            boolean configured = quotas != null && quotas.amount(entry.getKey()).isPresent();
            if (!configured) {
                missing.add(new ResourceShortage(
                        entry.getKey(),
                        entry.getValue(),
                        used.getOrDefault(entry.getKey(), 0L),
                        0));
            }
        }
        return missing;
    }

    private Denied deny(ScopeState state, DenialReason reason, List<ResourceShortage> shortages) {
        return new Denied(reason, shortages, snapshot(state));
    }

    private static Map<ResourceName, Long> used(ScopeState state) {
        Map<ResourceName, Long> used = new HashMap<>();
        for (Reservation reservation : state.held.values()) {
            reservation.resources().amounts()
                    .forEach((name, amount) -> used.merge(name, amount, InMemoryReservationStore::saturatingAdd));
        }
        return used;
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private UsageSnapshot snapshot(ScopeState state) {
        Map<ResourceName, Long> used = used(state);
        Map<ResourceName, ResourceUsage> resources = new LinkedHashMap<>();
        if (state.quotas != null) {
            for (Map.Entry<ResourceName, Long> entry : state.quotas.amounts().entrySet()) {
                resources.put(
                        entry.getKey(),
                        new ResourceUsage(used.getOrDefault(entry.getKey(), 0L), entry.getValue()));
            }
        }
        for (Map.Entry<ResourceName, Long> entry : used.entrySet()) {
            resources.putIfAbsent(entry.getKey(), new ResourceUsage(entry.getValue(), 0));
        }
        return new UsageSnapshot(state.scope, resources);
    }

    private ScopeState state(Scope scope) {
        return scopes.computeIfAbsent(scope, ScopeState::new);
    }

    private static final class ScopeState {
        private final Scope scope;
        private ResourceVector quotas;
        private final Map<ReservationId, Reservation> held = new LinkedHashMap<>();

        private ScopeState(Scope scope) {
            this.scope = scope;
        }
    }
}
