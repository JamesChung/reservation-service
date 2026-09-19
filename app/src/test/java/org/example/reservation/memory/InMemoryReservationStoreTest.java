package org.example.reservation.memory;

import java.time.Clock;
import org.example.reservation.ReservationStore;
import org.example.reservation.ReservationStoreContract;

class InMemoryReservationStoreTest extends ReservationStoreContract {

    @Override
    protected ReservationStore createStore(Clock clock) {
        return new InMemoryReservationStore(clock);
    }
}
