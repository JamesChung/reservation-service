package org.example.reservation;

/**
 * Why a reservation was not granted. Only {@link #INSUFFICIENT_CAPACITY} is waitable.
 */
public enum DenialReason {
    /** No quota stored for the scope, or a requested name is absent from the quota. */
    NO_QUOTA_CONFIGURED,
    /** Requested amount is greater than the configured limit; waiting cannot help. */
    REQUEST_EXCEEDS_QUOTA,
    /** Request fits the limit but current occupancy does not leave enough free. */
    INSUFFICIENT_CAPACITY
}
