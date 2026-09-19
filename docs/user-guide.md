# User guide

This module stores **occupancy**: how much of a budget is in use right now. A pipeline run, a pod, or any other unit of work takes a hold when it starts and gives it back when it finishes (or when the lease times out).

It does **not**:

- Limit requests per second
- Serialize jobs behind a lock
- Elect a leader
- Know what a “pipeline” or “tenant” is — those names are yours

The public surface is `ReservationStore`. Waiting, if you want it, is a separate helper (`ReservationRetries`).

## Mental model

```text
Scope  ── isolation boundary (quotas, uniqueness, list, usage)
  └── quota     named integer limits  (pipeline.runs=15, cpu.millis=8000, …)
  └── holds     one Reservation per running unit of work
```

| Idea | Type | Meaning |
|---|---|---|
| Isolation boundary | `Scope` | `type` + `id`, both caller vocabulary (`"pipelinesv2"` / `"acme"`). Factory `Scope.tenant("acme")` is just `new Scope("tenant", "acme")`. |
| Dimension | `ResourceName` | Opaque string matching `[A-Za-z0-9._-]+` (`pipeline.runs`, `cpu.millis`, `memory.bytes`). |
| Demand or quota | `ResourceVector` | Non-empty map of name → **positive** integer. Units are yours (slots, millicores, bytes). |
| Idempotency key | `ReservationId` | Unique **per scope**. A second admit with the same id and vector while held returns the existing hold. |
| Who first won | `Owner` | Recorded once at first grant. Never updated. Not part of the id. |
| Fence | `LeaseToken` | Unguessable generation id. `release` / `extend` must present this token. |
| Grant | `Reservation` | Snapshot of one generation (id, token, owner, scope, vector, timestamps). Not a handle, not `AutoCloseable`. |
| Lease length | `Duration` ttl | Inclusive bounds `LeasePolicy.MIN_TTL` (1s) … `MAX_TTL` (24h). |

All methods that touch a hold take a `Scope` (on the request or on the `Reservation`). That keeps a later Valkey cluster deployment on one hash tag / slot per tenant.

## Public types

| Type | Role |
|---|---|
| `ReservationStore` | Non-blocking occupancy API |
| `ReserveRequest` | Admit input: id, owner, scope, vector, ttl |
| `Reservation` | Granted hold snapshot |
| `Try<T>` | Success or failure (`Denied`, `NotFound`, …). Stand-in ABI until swapped for an out-of-repo `Try` |
| `Denied` + `DenialReason` | Admit refused. Only `INSUFFICIENT_CAPACITY` is waitable |
| `TimedOut` | Produced only by `ReservationRetries`, never by the store |
| `NotFound` | `find` / `quotas` when nothing is stored |
| `StaleLease` | `extend` with unknown, expired, or wrong token |
| `ReservationConflict` | Same id already held with a **different** vector |
| `ReservationException` | Store/transport failure (including unreadable schema on Valkey) |
| `UsageSnapshot` / `ResourceUsage` | Per-name `used` vs `limit` (`available()` = max(0, limit − used)) |
| `ReservationRetries` / `Sleeper` | Optional client-side poller |

## Configure quotas

```java
store.replaceQuotas(
        scope,
        ResourceVector.builder()
                .put(new ResourceName("pipeline.runs"), 15)
                .put(new ResourceName("cpu.millis"), 8_000)
                .build());
```

- **Full replace.** Names omitted from the new vector disappear from the quota. Live holds are **not** revoked; new admits just see less (or different) headroom.
- `quotas(scope)` is `NotFound` until the first successful replace (or after `deleteQuotas`).
- `deleteQuotas` returns the scope to unconfigured. Existing holds remain until release or TTL.

A request that names a dimension **not** in the quota is `NO_QUOTA_CONFIGURED` (hard deny). Configure every name v2 will ask for, even if v1 never uses them — or put v1 and v2 in different scopes (see the [CI sample](samples/ci-pipelines.md)).

A request larger than the configured limit (one pod asks for 32Gi when the cap is 16Gi) is `REQUEST_EXCEEDS_QUOTA`. Waiting cannot help.

## Admit (`tryReserve`)

Non-blocking. Never `TimedOut`. Either the whole vector is held, or nothing is written.

```java
Try<Reservation> result = store.tryReserve(new ReserveRequest(
        new ReservationId(runUuid.toString()),
        new Owner(workerId),
        scope,
        ResourceVector.of(new ResourceName("pipeline.runs"), 1),
        Duration.ofMinutes(5)));
```

| Outcome | Meaning |
|---|---|
| Success | New grant, **or** idempotent re-read of the live hold (same token, **original** owner and expiry) |
| `Denied` `INSUFFICIENT_CAPACITY` | Fits the cap, but live occupancy does not leave enough free. Waiting might help. |
| `Denied` `NO_QUOTA_CONFIGURED` | No quota, or a requested name is missing from it |
| `Denied` `REQUEST_EXCEEDS_QUOTA` | Amount > limit |
| `ReservationConflict` | This id is already held in this scope with a **different** vector |
| `ReservationException` | Backend failure |

Ids are unique **per scope**, not globally. The same string may be held in `pipelinesv1/acme` and `pipelinesv2/acme` at once.

Occupancy is summed **per resource name** across every live hold in the scope. A hold that only occupies `pipeline.runs` does not consume `cpu.millis`. A hold that occupies both is counted on both.

## Owner vs id

A reservation is occupancy, not leadership.

Two schedulers retrying the same `ReservationId` both receive the **same** token when it grants. The owner is whoever won **first**. Start Jenkins / the pod only if:

```java
if (!hold.owner().equals(new Owner(thisWorkerId))) {
    return; // another replica already owns this run
}
```

## Heartbeat and finish

Long work must `extend` before the lease ends (max 24h per extend, repeat as needed):

```java
Try<Reservation> refreshed = store.extend(hold, Duration.ofMinutes(5));
```

Wrong, expired, or unknown token → `StaleLease` (not a silent no-op). Use the returned snapshot for later extend/release (new `expiresAt`, same token).

```java
Try<Boolean> released = store.release(hold);
```

- `Success(true)` — this generation was held and is now gone
- `Success(false)` — unknown, already expired, or **wrong token** (for example a delayed release after the id was reused)

There is no `release(id)`. Passing only the id after reuse would drop someone else’s new hold (split-brain). Always pass the `Reservation` you observed.

Crash without release: the lease expires, occupancy is freed on the next mutating/read path that sweeps that scope.

## Observe

```java
store.find(scope, new ReservationId(runUuid.toString())); // NotFound if unknown/expired
store.list(scope);   // live holds
store.usage(scope);  // per-name used/limit after expiry sweep
```

`usage` includes every name in the current quota (used may be 0) and any names still held that are no longer in the quota (`limit` is then 0).

## `Try`

Stand-in for the caller’s `Try<T>`. Not a stable ABI — swapping the import later is a breaking change.

- `isSuccess()` / `isFailure()`
- `get()` — value, or throws the cause if it is a `RuntimeException`/`Error`
- `cause()` — on failure
- `Try.ok()` — successful `Void` mutation (`null` payload is allowed only here)

Failure causes you should switch on: `Denied`, `NotFound`, `StaleLease`, `ReservationConflict`, `TimedOut`, `InterruptedException`, `ReservationException`.

## `ReservationRetries` (optional)

The store never blocks. This helper polls `tryReserve` on the **caller thread** until success, a hard failure, or timeout.

You can ignore it completely and:

- fail the run immediately on `INSUFFICIENT_CAPACITY`, or
- persist “queued” in your own database and call `tryReserve` later

Use it only when some caller wants “sleep here until a slot frees up.”

```java
ReservationRetries retries = new ReservationRetries(); // 100ms poll, UTC clock, Thread.sleep
Try<Reservation> result = retries.reserve(store, request, Duration.ofSeconds(30));
```

| Result | Waits? |
|---|---|
| Grant | No |
| `INSUFFICIENT_CAPACITY` | Yes, until timeout |
| Other `Denied`, conflict, store errors | No |
| Still full at deadline | `TimedOut` (carries last `UsageSnapshot`) |
| `Duration.ZERO` | Single try; never `TimedOut` |
| Negative timeout | `IllegalArgumentException` |

Not FIFO: the first successful poll wins. Interrupt during sleep restores the interrupt flag and fails with `InterruptedException`. Do not run it on a Netty/event-loop thread.

Inject `Clock`, `Sleeper`, and poll interval in tests: `new ReservationRetries(clock, sleeper, Duration.ofMillis(20))`.

## Which backend

```java
// production / anything multi-process
try (ValkeyReservationStore store = ValkeyReservationStore.connect(
        ValkeySettings.parse(redisUri).withNamespace("ci"))) {
    // ...
}

// unit tests
ReservationStore store = new InMemoryReservationStore();
```

The API is store-agnostic. Contract tests in `testFixtures` (`ReservationStoreContract`) run against both. Valkey cannot inject a `Clock` (expiry is server `TIME`); those contract tests are skipped when `supportsInjectedClock()` is false.

Details of keys, Lua, and schema: [Valkey](valkey.md). End-to-end mapping for a CI platform: [CI pipelines sample](samples/ci-pipelines.md).

## End-to-end snippet

```java
import java.time.Duration;
import org.example.reservation.Denied;
import org.example.reservation.Owner;
import org.example.reservation.Reservation;
import org.example.reservation.ReservationId;
import org.example.reservation.ReservationRetries;
import org.example.reservation.ReservationStore;
import org.example.reservation.ReserveRequest;
import org.example.reservation.ResourceName;
import org.example.reservation.ResourceVector;
import org.example.reservation.Scope;
import org.example.reservation.TimedOut;
import org.example.reservation.Try;
import org.example.reservation.valkey.ValkeyReservationStore;
import org.example.reservation.valkey.ValkeySettings;

class Scheduler {
    private static final ResourceName RUNS = new ResourceName("pipeline.runs");

    private final ReservationStore store;
    private final ReservationRetries retries = new ReservationRetries();
    private final Owner me = new Owner("scheduler-pod-7f9");

    Scheduler(String redisUri) {
        this.store = ValkeyReservationStore.connect(
                ValkeySettings.parse(redisUri).withNamespace("ci"));
    }

    void onRunCreated(String teamId, String runUuid) {
        Scope scope = new Scope("pipelinesv2", teamId);
        ReserveRequest request = new ReserveRequest(
                new ReservationId(runUuid),
                me,
                scope,
                ResourceVector.of(RUNS, 1),
                Duration.ofMinutes(5));

        Try<Reservation> result = retries.reserve(store, request, Duration.ofSeconds(30));
        if (!result.isSuccess()) {
            Throwable cause = result.cause();
            if (cause instanceof TimedOut || cause instanceof Denied) {
                // queue or fail the run in the CI database
            }
            return;
        }
        Reservation hold = result.get();
        if (!hold.owner().equals(me)) {
            return;
        }
        startWork(hold);
    }

    void onHeartbeat(Reservation hold) {
        store.extend(hold, Duration.ofMinutes(5));
    }

    void onFinished(Reservation hold) {
        store.release(hold);
    }

    void startWork(Reservation hold) {
        // launch the job; keep `hold` for extend/release
    }
}
```
