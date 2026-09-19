package org.example.reservation;

import java.time.Duration;

/** Interruptible wait; inject a fake in tests. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    static Sleeper threadSleep() {
        return duration -> {
            long millis = duration.toMillis();
            int nanos = duration.toNanosPart() % 1_000_000;
            if (millis > 0 || nanos > 0) {
                Thread.sleep(millis, nanos);
            }
        };
    }
}
