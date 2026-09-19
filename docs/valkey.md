# Valkey backend

`ValkeyReservationStore` is the production `ReservationStore`. Atomic admit, release, and extend are **Lua scripts shipped in the JAR**, loaded with `SCRIPT LOAD` and run with `EVALSHA`.

A consuming application (CI platform, control plane, …) does **not** write Lua, `FUNCTION LOAD`, or invent key names. It calls `ReservationStore`. Cloud Valkey must allow `EVAL`, `EVALSHA`, and `SCRIPT LOAD`.

## Connect

```java
ValkeyReservationStore store = ValkeyReservationStore.connect(
        ValkeySettings.parse("redis://127.0.0.1:6379")
                .withTimeout(Duration.ofSeconds(5))
                .withNamespace("ci"));
```

- URI is Lettuce/`RedisURI` (`redis://` or `rediss://`, optional password).
- `connect` opens a **standalone** client, `PING`s, and loads every script. `close()` shuts the connection and client down (`AutoCloseable`).
- `namespace` is inserted inside the hash tag: `{rsv:ci:pipelinesv2:acme}` vs default `{rsv:pipelinesv2:acme}`. Use it when several apps or environments share one Valkey. It must not contain `{` or `}`.
- There is no Cluster/Sentinel client in this slice. Hash tags are already cluster-shaped so a later cluster deploy can keep each scope on one slot.

The `RedisCommands<String, String>` constructor is for tests that already own a client.

## Why Lua (and not FUNCTIONS)

Each mutating call must check quota, sum live occupancy, and write or refuse **as one unit**. On Valkey that unit is one `EVALSHA` on the scope’s two keys. Without a server-side script, concurrent admits could oversubscribe.

This module uses **EVALSHA**, not Redis/Valkey **FUNCTIONS** (`FUNCTION LOAD` + `FCALL`):

| | EVALSHA (what we do) | FUNCTIONS |
|---|---|---|
| Where the code lives | JAR (`/reservation/lua/*.lua`) | Persisted library inside the server |
| Load | `SCRIPT LOAD` at `ValkeyReservationStore` construct | `FUNCTION LOAD REPLACE` on every primary |
| Versioning | Each JAR has its own SHA. Old and new processes keep their own Lua | One named library in the DB; rolling JARs fight over it |
| After restart / `SCRIPT FLUSH` | `NOSCRIPT` → load that script once and retry | Survives restart (replicated) |
| Atomicity | Same (one script, server-blocked) | Same |

Occupancy logic is application code, not a stored procedure. `LuaScripts` keeps SHAs in a `ConcurrentHashMap` so concurrent `NOSCRIPT` reloads cannot corrupt lookups.

## Scripts

Sources: `app/src/main/resources/reservation/lua/`. Loaded from classpath `/reservation/lua/<name>.lua`.

| Script | Store method | Role |
|---|---|---|
| `replace_quotas.lua` | `replaceQuotas` | Write the quota HASH (full replace) |
| `delete_quotas.lua` | `deleteQuotas` | Delete the quota key |
| `quotas.lua` | `quotas` | Read quota |
| `try_reserve.lua` | `tryReserve` | Sweep expired readable holds, sum occupancy, admit or deny, `HSET` hold JSON |
| `release.lua` | `release` | Token-fenced `HDEL` |
| `extend.lua` | `extend` | Token-fenced expiry refresh; keeps extra JSON fields |
| `find.lua` | `find` | One hold by id |
| `list.lua` | `list` | Live readable holds |
| `usage.lua` | `usage` | Per-name used vs limit |
| `migrate_scope.lua` | `migrateScope` (package-private) | `RENAME` previous generation → current, or fail `SPLIT` |

The CI / caller app never names these. Jenkins vs Kubernetes is `Scope` + resource names on the request, not a different script.

`try_reserve` sums `used[name]` across **every live hold**, then checks only the names **in this request**. A Jenkins-style hold `{pipeline.runs: 1}` does not consume CPU. A K8s-style hold with four names is atomic across all four.

## Key layout (schema generation 1)

Generation sits **outside** the `{…}` hash tag:

```text
{rsv:[namespace:]<type>:<id>}:v1:quota     HASH   resourceName → limit (integer)
{rsv:[namespace:]<type>:<id>}:v1:holds     HASH   reservationId → JSON
```

Example with namespace `ci`, scope `("pipelinesv2", "acme")`:

```text
{rsv:ci:pipelinesv2:acme}:v1:quota
{rsv:ci:pipelinesv2:acme}:v1:holds
```

Putting `:v1:` **inside** the tag (`{rsv:v1:…}` vs `{rsv:v2:…}`) would be two cluster slots; Lua could not `RENAME` in one eval.

Hold JSON:

```json
{
  "v": 1,
  "id": "run-uuid",
  "token": "unguessable-lease",
  "owner": "scheduler-pod-7f9",
  "scopeType": "pipelinesv2",
  "scopeId": "acme",
  "resources": { "pipeline.runs": 1, "cpu.millis": 500 },
  "createdAt": 1710000000000,
  "expiresAt": 1710000300000
}
```

Expiry is Valkey `TIME` inside Lua (milliseconds), not an injected Java `Clock`. Lazy sweep `HDEL`s expired **readable** fields. There is no portable per-field TTL on the hash.

## Two version numbers

| Axis | Where | What it is for |
|---|---|---|
| **Key generation** | `:vN:` in the key name | Which pair of hashes this JAR reads/writes. A bump needs `migrate_scope`. |
| **Payload `v`** | JSON field `"v"` | Document shape. Additive fields do **not** bump the key generation. |

Today both are `1` (`ValkeySchema.CURRENT` and `MIN_READABLE`). `connect()` always uses generation 1.

Rules:

- Writers always write **current** generation keys and `"v": CURRENT`.
- Readers accept payload `v` in `[MIN_READABLE, CURRENT]` (missing `v` counts as 1).
- Rolling JARs N and N-1 may share one Valkey **only on the same key generation**.
- Additive JSON: ship readers that ignore unknown properties (Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false`), then writers that set the field. `extend` mutates the decoded table in place and keeps extra fields.

### Mixed payload versions (same keys)

A newer JAR may write `"v": 2` later. An old JAR (`CURRENT = 1`) must not destroy that document:

| Op | Future payload (`v` > this JAR) | Expired future payload |
|---|---|---|
| Occupancy (`try_reserve` / `usage`) | Counted if still live | Not counted |
| Lazy `HDEL` sweep | Left alone | Left alone |
| `list` / `find` | Omitted / `UNSUPPORTED` | Left in Redis |
| `release` / `extend` | `UNSUPPORTED` → Java `ReservationException` | same |

New grants stamp `"v": currentV`. `extend` currently rewrites `v` to current as well.

## `migrate_scope` (future key-generation bump)

Not a CI concern while `CURRENT == 1`. Java `migrateScope` is package-private and returns success immediately when generation ≤ 1.

When a future JAR ships `CURRENT = 2`, migration is **per scope**, not cluster-wide. The script never merges occupancy (that would double-count).

KEYS: previous quota, previous holds, current quota, current holds.

| Situation | Reply | Effect |
|---|---|---|
| Both generations have at least one key | `SPLIT` | Fail. Java → `ReservationException` |
| Current exists, previous empty | `OK` | No-op |
| Neither exists | `NONE` | No-op (empty tenant) |
| Previous exists, current empty | `OK` after `RENAME` | Atomic move; JSON bodies unchanged |

Intended rollout: drain or stop generation-1 writers for that scope, deploy generation-2 JARs, run `migrateScope` while v2 keys are still empty. Leftover v1 writes after migrate are a split and must be fixed by hand.

Hold-format changes (max TTL 24h) can often drain without a key bump. Quota/key-layout changes cannot drain; they need this rename path.

## What consuming apps do not do

- Do not add Lua for Jenkins vs Kubernetes, projects, or run UUIDs. Those are scopes, resource names, and hold field names.
- Do not put “pipelinesv1” in the `:vN:` infix. That infix is **this library’s storage generation**.
- Do not `FUNCTION LOAD` on startup.

## Limitations (known, not a roadmap)

From a module audit; not implemented in this documentation slice:

- Every occupancy op `HGETALL`s the holds hash (cost grows with live holds in the scope).
- `Scope` type/id may contain `{` / `}` and would break the cluster hash tag.
- `./gradlew check` does not run `integrationTest`. Local Valkey tests are Apple `container` only.
- The `RedisCommands` constructor is public (easy to leak a client). Prefer `connect`.
- `extend` stamps payload `v` to current.

See also [testing](testing.md) for how integration tests load these scripts against a real Valkey.
