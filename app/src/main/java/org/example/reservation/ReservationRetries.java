package org.example.reservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Client-side wait: poll {@link ReservationStore#tryReserve} until timeout.
 *
 * <p>Retries only {@link DenialReason#INSUFFICIENT_CAPACITY}. Hard denials, conflicts, and
 * store failures return immediately. Not FIFO — the first successful poll wins.
 *
 * <p>A reservation is occupancy, not leadership. Two callers polling the same id both
 * receive the same token when it grants; run work only if {@link Reservation#owner()}
 * matches this worker.
 */
public final class ReservationRetries {

    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration pollInterval;

    public ReservationRetries() {
        this(Clock.systemUTC(), Sleeper.threadSleep(), LeasePolicy.DEFAULT_POLL_INTERVAL);
    }

    public ReservationRetries(Clock clock, Sleeper sleeper, Duration pollInterval) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.pollInterval = LeasePolicy.requirePollInterval(pollInterval);
    }

    /**
     * {@link Duration#ZERO} is a single {@code tryReserve} (never {@link TimedOut}).
     * Negative timeouts throw {@link IllegalArgumentException}.
     */
    public Try<Reservation> reserve(ReservationStore store, ReserveRequest request, Duration timeout) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be non-negative");
        }
        Instant deadline = clock.instant().plus(timeout);
        UsageSnapshot lastUsage = null;
        while (true) {
            Try<Reservation> attempt = store.tryReserve(request);
            if (attempt.isSuccess()) {
                return attempt;
            }
            Throwable cause = attempt.cause();
            if (cause instanceof Denied denied) {
                lastUsage = denied.usage();
                if (denied.reason() != DenialReason.INSUFFICIENT_CAPACITY || timeout.isZero()) {
                    return attempt;
                }
            } else {
                return attempt;
            }
            Instant now = clock.instant();
            if (!now.isBefore(deadline)) {
                return Try.failure(new TimedOut(lastUsage));
            }
            Duration remaining = Duration.between(now, deadline);
            Duration sleep = remaining.compareTo(pollInterval) < 0 ? remaining : pollInterval;
            try {
                sleeper.sleep(sleep);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Try.failure(interrupted);
            }
        }
    }
}
