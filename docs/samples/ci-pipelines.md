# Sample: CI pipelines

How a CI platform would use this module. This is a worked example, not a second product. The CI app is a **client** of `ReservationStore`. It does not add Lua, keys, or schema.

## Run it

A scripted simulation (tight quotas so denials show up) lives in `app/src/sample/java` and is **not** in `app.jar`:

```text
./gradlew runPipelineSample
./gradlew runPipelineSample --args='--valkey redis://127.0.0.1:6379'
```

In-memory by default (no Valkey). You should see:

- two Jenkins runs fill `pipelinesv1` (`pipeline.runs=2/2`)
- a third Jenkins run `DENIED INSUFFICIENT_CAPACITY`
- two K8s runs still **admit** on `pipelinesv2` (independent scope)
- oversized K8s CPU → `REQUEST_EXCEEDS_QUOTA`
- after one Jenkins `release`, `ReservationRetries` admits the parked v1 run

Definition UUIDs are printed as catalog only; every `ReservationId` is a fresh run UUID.

## Domain

Teams create a **project** (a link to SCM, for example GitHub). A project can define both **pipelinesv1** and **pipelinesv2**, and many definitions of each type.

| Concept | What it is | In this module? |
|---|---|---|
| Team | Owns compute budget | Often the `Scope` id |
| Project | GitHub repo link | CI database only |
| Definition | Jenkinsfile or v2 workflow; has a **definition UUID** | CI database only. Creating/editing it does not `tryReserve`. |
| Run | One execution; has a **run UUID** | One `Reservation` until `release` or TTL |

```text
team
  └── project  (SCM link)                    → CI DB
        ├── pipelinesv1 definitions (many)   → CI DB; definition UUID is not a hold
        ├── pipelinesv2 definitions (many)
        └── runs                             → ReservationId = run UUID
```

Two architectures, **no shared limits** (different compute):

- **pipelinesv1** — Jenkins. No CPU/memory/disk in this store. Cap concurrent runs.
- **pipelinesv2** — Kubernetes. Concurrent runs **plus** CPU, memory, disk from the pod spec.

Many definitions of the same type **share** that architecture’s pool.

## Mapping

| CI idea | Module type |
|---|---|
| Jenkins pool vs K8s pool | Two scopes: `new Scope("pipelinesv1", teamId)` and `new Scope("pipelinesv2", teamId)` |
| Run UUID | `new ReservationId(runUuid.toString())` |
| Worker that starts the job | `new Owner(jenkinsControllerId)` or `new Owner(schedulerPodName)` |
| Jenkins demand | `{ pipeline.runs: 1 }` |
| K8s demand | `{ pipeline.runs: 1, cpu.millis, memory.bytes, disk.bytes }` |
| Project id, definition UUID | CI run row — **not** fields on `Reservation` |

`Scope` is the isolation boundary: quotas, id uniqueness, `list`, `usage`, and Lua `HGETALL` are per scope. If budgets are per GitHub repo instead of per team, use `projectId` as the scope id. Still **one scope per architecture**.

Do **not** put both products in `Scope.tenant("acme")` with `pipelinesv1.runs` vs `pipelinesv2.runs`. That still shares one holds hash: one `replaceQuotas` overwrites both products, `list`/`usage` mix Jenkins and K8s, and ids are unique across both.

Do **not** encode architecture in the Valkey `:v1:` infix. That is this library’s storage generation. Payload JSON `"v": 1` is the document version, not pipelinesv1.

## Two UUIDs

Use the **run UUID** as `ReservationId`. Never the definition UUID.

If you passed the definition UUID:

- The first run of that definition grants a hold.
- A second concurrent run with the **same** vector gets the **same** token (idempotent grant), not a second slot. Only `owner` should start work, so the second executor stands down — N runs collapsed into 1 occupancy.
- A second run with a **different** vector (v2 pods with different CPU) is `ReservationConflict`.

A definition can have many in-flight runs. Each run occupies its own slot (and, for v2, its own resources).

## Bootstrap (control plane)

Once per team (or per project), not per definition:

```java
static final ResourceName RUNS   = new ResourceName("pipeline.runs");
static final ResourceName CPU    = new ResourceName("cpu.millis");
static final ResourceName MEMORY = new ResourceName("memory.bytes");
static final ResourceName DISK   = new ResourceName("disk.bytes");

ReservationStore store = ValkeyReservationStore.connect(
        ValkeySettings.parse(redisUri).withNamespace("ci"));

store.replaceQuotas(
        new Scope("pipelinesv1", "acme"),
        ResourceVector.of(RUNS, 20));

store.replaceQuotas(
        new Scope("pipelinesv2", "acme"),
        ResourceVector.builder()
                .put(RUNS, 15)
                .put(CPU, 8_000)
                .put(MEMORY, 16L << 30)
                .put(DISK, 100L << 30)
                .build());
```

Those numbers are independent. Filling 20 Jenkins slots does not consume K8s CPU. A K8s deny on memory does not block Jenkins.

You must put CPU/memory/disk on the **v2** quota because a v2 admit that names `cpu.millis` otherwise is `NO_QUOTA_CONFIGURED`. Jenkins never requests those names, so it never consumes them.

Lowering a quota does not kill running pipelines.

## Admit

```java
Owner worker = new Owner(thisWorkerId);
Duration ttl = Duration.ofMinutes(5);

// any pipelinesv1 definition — vector does not mention the definition
ReserveRequest jenkins = new ReserveRequest(
        new ReservationId(runUuid.toString()),
        worker,
        new Scope("pipelinesv1", teamId),
        ResourceVector.of(RUNS, 1),
        ttl);

// any pipelinesv2 definition — amounts from this run’s pod spec
ReserveRequest k8s = new ReserveRequest(
        new ReservationId(runUuid.toString()),
        worker,
        new Scope("pipelinesv2", teamId),
        ResourceVector.builder()
                .put(RUNS, 1)
                .put(CPU, pod.cpuMillis())
                .put(MEMORY, pod.memoryBytes())
                .put(DISK, pod.diskBytes())
                .build(),
        ttl);
```

Atomic vector: v2 never occupies a run slot without also fitting CPU/memory/disk.

Then either:

```java
Try<Reservation> granted = store.tryReserve(jenkins);           // fail-fast
Try<Reservation> granted = retries.reserve(store, k8s, wait);   // optional poll
```

`ReservationRetries` is optional. Queue in the CI database and retry `tryReserve` yourself if you do not want a blocking poll. See the [user guide](../user-guide.md#reservationretries-optional).

On success, start work only if `hold.owner().equals(worker)`. Heartbeat with `store.extend(hold, ttl)`. On terminal state, `store.release(hold)`. Recover after scheduler restart with `store.find(scope, new ReservationId(runUuid))`.

### Lifecycle

```text
create project / save definition     → CI DB only
start run                            → tryReserve(architecture scope, run UUID, vector)
run heartbeat                        → extend
run finished / cancelled / crashed   → release  (or TTL)
delete project                       → cancel its runs (release); do not delete the
                                       team scope unless the team is going away
```

## What Valkey looks like

No new keys or scripts. Namespace `ci`, team-level pools, team `acme`:

```text
{rsv:ci:pipelinesv1:acme}:v1:quota
    pipeline.runs = 20

{rsv:ci:pipelinesv1:acme}:v1:holds
    <jenkins-run-uuid>  { "v": 1, "resources": { "pipeline.runs": 1 }, ... }


{rsv:ci:pipelinesv2:acme}:v1:quota
    pipeline.runs = 15
    cpu.millis    = 8000
    memory.bytes  = ...
    disk.bytes    = ...

{rsv:ci:pipelinesv2:acme}:v1:holds
    <k8s-run-uuid>  { "v": 1, "resources": {
         "pipeline.runs": 1, "cpu.millis": 500, "memory.bytes": ..., "disk.bytes": ...
    }, ... }
```

Existing `try_reserve.lua` runs on **one** quota key + **one** holds key. A Jenkins eval never reads the K8s hashes. Occupancy cannot leak across architectures.

`usage(jenkinsScope)` shows Jenkins runs only. `usage(k8sScope)` shows K8s runs plus resource occupancy. `store.list` is live occupancy in that compute pool, not “runs in this GitHub repo” — keep project/definition on the CI run record.

## Optional: cap one definition

Only if you need “this expensive workflow at most 1 at a time” **in addition** to the team pool. The hold id remains the **run** UUID.

Simplest: a second scope you admit separately (not atomic with the team pool):

```java
new Scope("pipelinesv2-def", definitionUuid)  // quota { pipeline.runs: 1 }
```

Atomic with the team pool: extra name on the team v2 quota, `def.<definitionUuid>.runs = 1`, and include it in the v2 request vector. That name must appear in `replaceQuotas` for every definition you cap — keep it rare. Most CI platforms only cap the compute pool and let definitions share it.

## Pitfalls

- Mixing Jenkins and K8s in one `Scope` when they must not share limits.
- Using the **definition** UUID as `ReservationId`.
- Putting architecture into `:v1:` / `:v2:` key generation.
- Skipping `extend` on jobs that can outlive the TTL (1s–24h per lease).
- `release` by id only — the snapshot’s `LeaseToken` fences reuse after expiry.
- Requesting CPU/memory/disk on Jenkins holds unless Jenkins should consume the K8s pool.
- Expecting `ReservationRetries` to be a fair queue. It is not; first successful poll wins.
