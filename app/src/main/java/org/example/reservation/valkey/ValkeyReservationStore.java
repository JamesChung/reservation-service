package org.example.reservation.valkey;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.example.reservation.LeasePolicy;
import org.example.reservation.NotFound;
import org.example.reservation.Reservation;
import org.example.reservation.ReservationConflict;
import org.example.reservation.ReservationException;
import org.example.reservation.ReservationId;
import org.example.reservation.ReservationStore;
import org.example.reservation.ReserveRequest;
import org.example.reservation.ResourceVector;
import org.example.reservation.Scope;
import org.example.reservation.StaleLease;
import org.example.reservation.Try;
import org.example.reservation.UsageSnapshot;

/**
 * {@link ReservationStore} backed by Valkey. All keys for a scope share a hash tag so
 * a later cluster deployment can keep Lua on one slot. Expiry is Valkey {@code TIME}.
 *
 * <h2>Layout (schema generation 1)</h2>
 * {@code {rsv:<type>:<id>}:v1:quota} and {@code :v1:holds}. Generation is outside the tag
 * so a v2 pair hashes to the same slot and can be renamed atomically. Hold JSON includes
 * {@code "v":1} plus id, token, owner, scope, resources, createdAt, expiresAt.
 *
 * <h2>EVALSHA</h2>
 * Scripts ship in the JAR, are {@code SCRIPT LOAD}ed at construct, and run with
 * {@code EVALSHA}. {@code NOSCRIPT} (restart, failover, {@code SCRIPT FLUSH}) reloads
 * that script once. This is application logic, not Valkey FUNCTIONS.
 *
 * <h2>Rolling upgrades</h2>
 * JARs N and N-1 may share a Valkey only while they use the <em>same key generation</em>.
 * Additive JSON fields are ignored by old Jackson and preserved by {@code extend}.
 * A hold with {@code v} newer than this client is not swept and is not returned as a
 * grant. A key-generation bump is {@link #migrateScope}: empty current + present
 * previous → {@code RENAME}; both present → failure (do not merge occupancy).
 *
 * <p>Use {@link #connect(ValkeySettings)} in production. The {@link RedisCommands}
 * constructor is for tests that already own the client.
 */
public final class ValkeyReservationStore implements ReservationStore, AutoCloseable {

    private final RedisCommands<String, String> commands;
    private final ReservationKeys reservationKeys;
    private final LuaScripts lua;
    private final ReservationCodec codec = new ReservationCodec();
    private final RedisClient ownedClient;
    private final StatefulRedisConnection<String, String> ownedConnection;

    public ValkeyReservationStore(RedisCommands<String, String> commands) {
        this(commands, new ReservationKeys(), null, null);
    }

    ValkeyReservationStore(RedisCommands<String, String> commands, ReservationKeys keys) {
        this(commands, keys, null, null);
    }

    private ValkeyReservationStore(
            RedisCommands<String, String> commands,
            ReservationKeys keys,
            RedisClient ownedClient,
            StatefulRedisConnection<String, String> ownedConnection) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.reservationKeys = Objects.requireNonNull(keys, "keys");
        this.lua = new LuaScripts(commands);
        this.ownedClient = ownedClient;
        this.ownedConnection = ownedConnection;
    }

    /**
     * Opens a standalone Lettuce connection, pings, and loads Lua scripts.
     * {@link #close()} shuts the connection and client down.
     */
    public static ValkeyReservationStore connect(ValkeySettings settings) {
        Objects.requireNonNull(settings, "settings");
        RedisClient client = RedisClient.create(settings.redisUri());
        StatefulRedisConnection<String, String> connection = null;
        try {
            connection = client.connect();
            String pong = connection.sync().ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new ReservationException("unexpected PING reply: " + pong);
            }
            return new ValkeyReservationStore(
                    connection.sync(),
                    new ReservationKeys(settings.namespace(), ValkeySchema.CURRENT),
                    client,
                    connection);
        } catch (RuntimeException e) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                    // closing after a failed handshake
                }
            }
            client.shutdown();
            if (e instanceof ReservationException) {
                throw e;
            }
            throw new ReservationException(e.getMessage(), e);
        }
    }

    /** Test hook: wipe the selected logical DB. */
    void flushForTests() {
        commands.flushdb();
    }

    /**
     * Move previous key generation onto this store's generation for {@code scope}.
     * No-op when this store is generation 1 or the previous keys are absent.
     * Fails if both generations already have data (would double-count occupancy).
     */
    Try<Void> migrateScope(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        return run(() -> {
            int generation = reservationKeys.generation();
            if (generation <= 1) {
                return Try.ok();
            }
            ReservationKeys previous = reservationKeys.withGeneration(generation - 1);
            String raw = lua.eval(
                    LuaScripts.MIGRATE_SCOPE,
                    keys(
                            previous.quota(scope),
                            previous.holds(scope),
                            reservationKeys.quota(scope),
                            reservationKeys.holds(scope)));
            if ("OK".equals(raw) || "NONE".equals(raw)) {
                return Try.ok();
            }
            if ("SPLIT".equals(raw)) {
                return Try.failure(new ReservationException(
                        "schema split for " + scope + ": both v" + (generation - 1)
                                + " and v" + generation + " keys exist"));
            }
            throw new ReservationException("unexpected migrate_scope reply: " + raw);
        });
    }

    @Override
    public void close() {
        if (ownedConnection != null) {
            ownedConnection.close();
        }
        if (ownedClient != null) {
            ownedClient.shutdown();
        }
    }

    @Override
    public Try<Void> replaceQuotas(Scope scope, ResourceVector quotas) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(quotas, "quotas");
        return run(() -> {
            expect(lua.eval(LuaScripts.REPLACE_QUOTAS, keys(reservationKeys.quota(scope)), codec.quotasJson(quotas)), "VOID");
            return Try.ok();
        });
    }

    @Override
    public Try<Void> deleteQuotas(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        return run(() -> {
            expect(lua.eval(LuaScripts.DELETE_QUOTAS, keys(reservationKeys.quota(scope))), "VOID");
            return Try.ok();
        });
    }

    @Override
    public Try<ResourceVector> quotas(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        return run(() -> {
            String raw = lua.eval(LuaScripts.QUOTAS, keys(reservationKeys.quota(scope)));
            if (raw.equals("NOT_FOUND")) {
                return Try.failure(new NotFound("no quota for " + scope));
            }
            return Try.success(codec.quotas(body(raw, "QUOTA")));
        });
    }

    @Override
    public Try<Reservation> tryReserve(ReserveRequest request) {
        Objects.requireNonNull(request, "request");
        return run(() -> {
            Scope scope = request.scope();
            String raw = lua.eval(
                    LuaScripts.TRY_RESERVE,
                    keys(reservationKeys.quota(scope), reservationKeys.holds(scope)),
                    request.id().value(),
                    request.owner().value(),
                    scope.type(),
                    scope.id(),
                    codec.resourcesJson(request.resources()),
                    Long.toString(request.ttl().toMillis()),
                    UUID.randomUUID().toString(),
                    payloadVersionArg());
            if (raw.equals("UNSUPPORTED")) {
                return Try.failure(unsupportedSchema());
            }
            if (raw.startsWith("OK ")) {
                return Try.success(codec.hold(raw.substring(3)));
            }
            if (raw.startsWith("DENIED ")) {
                return Try.failure(codec.denied(raw.substring(7)));
            }
            if (raw.startsWith("CONFLICT ")) {
                return Try.failure(new ReservationConflict(codec.hold(raw.substring(9))));
            }
            throw new ReservationException("unexpected try_reserve reply: " + raw);
        });
    }

    @Override
    public Try<Boolean> release(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        return run(() -> {
            String raw = lua.eval(
                    LuaScripts.RELEASE,
                    keys(reservationKeys.holds(reservation.scope())),
                    reservation.id().value(),
                    reservation.token().value(),
                    payloadVersionArg());
            if (raw.equals("TRUE")) {
                return Try.success(true);
            }
            if (raw.equals("FALSE")) {
                return Try.success(false);
            }
            if (raw.equals("UNSUPPORTED")) {
                return Try.failure(unsupportedSchema());
            }
            throw new ReservationException("unexpected release reply: " + raw);
        });
    }

    @Override
    public Try<Reservation> extend(Reservation reservation, Duration ttl) {
        Objects.requireNonNull(reservation, "reservation");
        LeasePolicy.requireTtl(ttl);
        return run(() -> {
            String raw = lua.eval(
                    LuaScripts.EXTEND,
                    keys(reservationKeys.holds(reservation.scope())),
                    reservation.id().value(),
                    reservation.token().value(),
                    Long.toString(ttl.toMillis()),
                    payloadVersionArg());
            if (raw.equals("STALE")) {
                return Try.failure(new StaleLease(reservation.id()));
            }
            if (raw.equals("UNSUPPORTED")) {
                return Try.failure(unsupportedSchema());
            }
            if (raw.startsWith("OK ")) {
                return Try.success(codec.hold(raw.substring(3)));
            }
            throw new ReservationException("unexpected extend reply: " + raw);
        });
    }

    @Override
    public Try<Reservation> find(Scope scope, ReservationId id) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(id, "id");
        return run(() -> {
            String raw = lua.eval(
                    LuaScripts.FIND,
                    keys(reservationKeys.holds(scope)),
                    id.value(),
                    payloadVersionArg());
            if (raw.equals("NOT_FOUND")) {
                return Try.failure(new NotFound("no reservation " + id.value() + " in " + scope));
            }
            if (raw.equals("UNSUPPORTED")) {
                return Try.failure(unsupportedSchema());
            }
            return Try.success(codec.hold(body(raw, "OK")));
        });
    }

    @Override
    public Try<UsageSnapshot> usage(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        return run(() -> {
            String raw = lua.eval(
                    LuaScripts.USAGE,
                    keys(reservationKeys.quota(scope), reservationKeys.holds(scope)),
                    scope.type(),
                    scope.id(),
                    payloadVersionArg());
            return Try.success(codec.usage(body(raw, "USAGE")));
        });
    }

    @Override
    public Try<List<Reservation>> list(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        return run(() -> {
            String raw = lua.eval(LuaScripts.LIST, keys(reservationKeys.holds(scope)), payloadVersionArg());
            return Try.success(codec.holds(body(raw, "LIST")));
        });
    }

    private static String payloadVersionArg() {
        return Integer.toString(ValkeySchema.CURRENT);
    }

    private static ReservationException unsupportedSchema() {
        return new ReservationException("hold schema version is newer than this client");
    }

    private static String[] keys(String... keys) {
        return keys;
    }

    private static String body(String raw, String prefix) {
        String expected = prefix + " ";
        if (!raw.startsWith(expected)) {
            throw new ReservationException("expected " + prefix + " reply, got: " + raw);
        }
        return raw.substring(expected.length());
    }

    private static void expect(String raw, String expected) {
        if (!expected.equals(raw)) {
            throw new ReservationException("expected " + expected + ", got: " + raw);
        }
    }

    private static <T> Try<T> run(SupplierTry<T> action) {
        try {
            return action.get();
        } catch (ReservationException e) {
            return Try.failure(e);
        } catch (RuntimeException e) {
            return Try.failure(new ReservationException(e.getMessage(), e));
        }
    }

    @FunctionalInterface
    private interface SupplierTry<T> {
        Try<T> get();
    }
}
