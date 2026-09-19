package org.example.reservation;

import java.util.Objects;
import java.util.function.Function;

/**
 * Stand-in for the caller's {@code Try<T>}. Not a stable ABI — swap the import when wiring
 * the real type.
 *
 * <p>{@link Success} is a value ({@code null} only for {@code Try<Void>} mutations).
 * {@link Failure} carries a {@link Throwable}: {@link Denied}, {@link NotFound},
 * {@link StaleLease}, {@link ReservationConflict}, {@link TimedOut},
 * {@link InterruptedException}, or {@link ReservationException}.
 */
public sealed interface Try<T> permits Try.Success, Try.Failure {

    record Success<T>(T value) implements Try<T> {}

    record Failure<T>(Throwable cause) implements Try<T> {
        public Failure {
            Objects.requireNonNull(cause, "cause");
        }
    }

    static <T> Try<T> success(T value) {
        return new Success<>(value);
    }

    static <T> Try<T> failure(Throwable cause) {
        return new Failure<>(cause);
    }

    /** Completed mutation with no payload. */
    static Try<Void> ok() {
        return success(null);
    }

    default boolean isSuccess() {
        return this instanceof Success<T>;
    }

    default boolean isFailure() {
        return this instanceof Failure<T>;
    }

    /**
     * Value of a success. On failure, throws the cause if it is a {@link RuntimeException}
     * or {@link Error}; otherwise {@link IllegalStateException} wrapping the cause.
     */
    default T get() {
        if (this instanceof Success<T> success) {
            return success.value();
        }
        Throwable cause = ((Failure<T>) this).cause();
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Try is a failure", cause);
    }

    default Throwable cause() {
        if (this instanceof Failure<T> failure) {
            return failure.cause();
        }
        throw new IllegalStateException("Try is a success");
    }

    default <U> Try<U> map(Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (this instanceof Success<T> success) {
            return success(mapper.apply(success.value()));
        }
        return failure(((Failure<T>) this).cause());
    }
}
