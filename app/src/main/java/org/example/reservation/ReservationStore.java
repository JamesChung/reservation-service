package org.example.reservation;

import java.time.Duration;
import java.util.List;

/**
 * Non-blocking occupancy store: hold a vector of resources in a {@link Scope} until
 * fenced release or lease expiry.
 *
 * <p>This is not a QPS limiter and not a job mutex. A running pipeline occupies capacity;
 * a finished or crashed one must {@link #release} or let the lease expire. Two workers
 * retrying the same {@link ReservationId} share one hold; start work only if
 * {@link Reservation#owner()} is this worker.
 *
 * <p>Waiting is {@link ReservationRetries}, not this interface. Implementations must be
 * linearizable per scope and cluster-safe (every method that touches a hold takes a
 * {@link Scope}, via the argument or {@link Reservation#scope()}).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Atomic vector.</b> Every name in the request is held, or none are.</li>
 *   <li><b>Fencing.</b> Each grant has a unique {@link LeaseToken}. {@link #release} and
 *       {@link #extend} apply only to that generation. After expiry, the same id may be
 *       granted again with a new token; a delayed release of the old token is a no-op.</li>
 *   <li><b>Idempotent grant.</b> Same id, scope, and vector while held returns the existing
 *       reservation (same token, same owner, original expiry). Owner is not updated.
 *       Same id with a different scope or vector is {@link ReservationConflict}.</li>
 *   <li><b>Release.</b> {@code Success(true)} if this token’s generation was held and is now
 *       released. {@code Success(false)} if unknown, expired, or wrong token.</li>
 *   <li><b>Extend.</b> Wrong/expired token is {@link StaleLease}, not a silent no-op.</li>
 *   <li><b>Quotas.</b> {@link #replaceQuotas} is an atomic full replace. {@link #deleteQuotas}
 *       returns the scope to unconfigured. Existing holds are never revoked.</li>
 *   <li><b>Errors.</b> Null/invalid args throw {@link NullPointerException} /
 *       {@link IllegalArgumentException}. Everything else is {@link Try}: {@link Denied},
 *       {@link NotFound}, {@link StaleLease}, {@link ReservationConflict},
 *       {@link ReservationException}.</li>
 * </ul>
 */
public interface ReservationStore {

    Try<Void> replaceQuotas(Scope scope, ResourceVector quotas);

    Try<Void> deleteQuotas(Scope scope);

    /** {@link NotFound} if {@link #replaceQuotas} has never been called (or was deleted). */
    Try<ResourceVector> quotas(Scope scope);

    /**
     * Non-blocking admit. Never waits. Never fails with {@link TimedOut}.
     */
    Try<Reservation> tryReserve(ReserveRequest request);

    /**
     * Drops this generation and frees its capacity.
     *
     * @return {@code Success(true)} if this token was held and is now released
     */
    Try<Boolean> release(Reservation reservation);

    /**
     * Heartbeat: same token, expiry now+ttl.
     * {@link StaleLease} if unknown, expired, or token mismatch. {@code ttl} must satisfy
     * {@link LeasePolicy}.
     */
    Try<Reservation> extend(Reservation reservation, Duration ttl);

    /** {@link NotFound} if unknown or expired. */
    Try<Reservation> find(Scope scope, ReservationId id);

    /**
     * Occupancy of {@code scope} after expiring stale leases.
     * Names with no quota and no holds are omitted.
     */
    Try<UsageSnapshot> usage(Scope scope);

    /** Live holds in {@code scope} after expiring stale leases. */
    Try<List<Reservation>> list(Scope scope);
}
