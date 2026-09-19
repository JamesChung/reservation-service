package org.example.reservation;

import java.util.List;
import java.util.Objects;

/**
 * The demand was not admitted. Carried as {@link Try.Failure#cause()}.
 *
 * {@link DenialReason#INSUFFICIENT_CAPACITY} is the only reason
 * {@link ReservationRetries} will poll.
 */
public final class Denied extends RuntimeException {

    private final DenialReason reason;
    private final List<ResourceShortage> shortages;
    private final UsageSnapshot usage;

    public Denied(DenialReason reason, List<ResourceShortage> shortages, UsageSnapshot usage) {
        super(reason.name());
        this.reason = Objects.requireNonNull(reason, "reason");
        this.shortages = List.copyOf(Objects.requireNonNull(shortages, "shortages"));
        this.usage = Objects.requireNonNull(usage, "usage");
    }

    public DenialReason reason() {
        return reason;
    }

    public List<ResourceShortage> shortages() {
        return shortages;
    }

    public UsageSnapshot usage() {
        return usage;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
