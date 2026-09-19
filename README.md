# Reservation store

A Java 21 occupancy/quota library. A **running piece of work** holds capacity until it is released or its lease expires. That is not a QPS rate limiter, not a job mutex, and not leadership election.

Package: `org.example.reservation` (Gradle module `:app`).

Two backends implement the same `ReservationStore` interface:

| Backend | When to use |
|---|---|
| `ValkeyReservationStore` | Shared across processes. Atomic admit is Lua on Valkey (`EVALSHA`). |
| `InMemoryReservationStore` | Unit tests / a single process. Not a production limiter. |

## Quick start

Configure a quota, admit a hold, heartbeat while work runs, then release:

```java
ReservationStore store = ValkeyReservationStore.connect(
        ValkeySettings.parse("redis://127.0.0.1:6379").withNamespace("ci"));

Scope team = new Scope("pipelinesv2", "acme");
ResourceName runs = new ResourceName("pipeline.runs");

store.replaceQuotas(team, ResourceVector.of(runs, 15));

Try<Reservation> granted = store.tryReserve(new ReserveRequest(
        new ReservationId(runUuid.toString()),
        new Owner(workerId),
        team,
        ResourceVector.of(runs, 1),
        Duration.ofMinutes(5)));

if (granted.isSuccess()) {
    Reservation hold = granted.get();
    if (hold.owner().equals(new Owner(workerId))) {
        // start work, then store.extend(hold, ttl) and store.release(hold)
    }
}
```

`tryReserve` never waits. If a caller wants “block this thread until capacity or timeout,” use optional `ReservationRetries`. You can ignore that helper and poll or queue in your own app.

For tests without Valkey:

```java
ReservationStore store = new InMemoryReservationStore();
```

## Commands

| Command | What it does |
|---|---|
| `./gradlew test` | In-memory unit tests. No container, no Lua. |
| `./gradlew integrationTest` | Store contract against `valkey/valkey:8` via Apple `container`. Exercises Lua. |
| `./gradlew runPipelineSample` | Simulated Jenkins + K8s runs (in-memory). See [CI pipelines sample](docs/samples/ci-pipelines.md). |
| `scripts/valkey-up.sh` | Long-lived local Valkey on `127.0.0.1:6379` for manual use. Tests do not use it. |

`integrationTest` is not part of `./gradlew check`. It skips if the Apple container CLI/apiserver is not running.

## Documentation

- [User guide](docs/user-guide.md) — concepts, public API, admit/release, retries
- [Valkey](docs/valkey.md) — keys, Lua, schema generations, rolling upgrades
- [Sample: CI pipelines](docs/samples/ci-pipelines.md) — Jenkins + Kubernetes workloads
- [Testing and Apple Container](docs/testing.md) — how tests start Valkey; Testcontainers research
