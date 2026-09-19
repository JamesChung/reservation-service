# Testing and Apple Container

This repo’s Valkey tests start Linux containers with Apple’s `container` CLI, not Docker Desktop and not Testcontainers. This page is how that works, why Testcontainers Java cannot drive `container` directly, and what other OSS libraries exist.

## What this repo runs

| Command | Backend | Lua? | Container? |
|---|---|---|---|
| `./gradlew test` | `InMemoryReservationStore` | No | No |
| `./gradlew integrationTest` | `ValkeyReservationStore` against `valkey/valkey:8` | Yes (`SCRIPT LOAD` + `EVALSHA`) | Yes — Apple `container` |
| `./gradlew check` | Same as `test` | No | No — `integrationTest` is **not** wired to `check` |

Shared contract: `ReservationStoreContract` in `app/src/testFixtures`. In-memory and Valkey both extend it. Valkey sets `supportsInjectedClock()` to false (expiry is server `TIME`).

`scripts/valkey-up.sh` starts a long-lived `reservation-valkey` on `127.0.0.1:6379` for humans. Integration tests **do not** use it; they pick their own name and port.

## How integration tests start Valkey

`ValkeyReservationStoreTest` registers a JUnit 5 extension once for the class:

```java
@RegisterExtension
static final AppleContainerValkey VALKEY = new AppleContainerValkey();
```

`AppleContainerValkey` (`BeforeAllCallback` / `AfterAllCallback`) shells out with `ProcessBuilder`. It does **not** speak the Docker Engine API.

Before any test:

1. Resolve the `container` binary (`PATH`, then `/opt/homebrew/bin/container`, then `/usr/local/bin/container`).
2. Run `container system status`. If the binary is missing or the apiserver is not running, the class is **skipped** (`TestAbortedException`), not failed.
3. Bind a free localhost port (`new ServerSocket(0)`).
4. Run:
   ```text
   container run -d --rm \
     --name reservation-valkey-<uuid> \
     -p 127.0.0.1:<port>:6379 \
     valkey/valkey:8
   ```
5. Lettuce connects and waits up to 60s for `PING` → `PONG`.
6. `new ValkeyReservationStore(connection.sync())` — that loads every Lua script.

Each test calls package-private `flushForTests()` (`FLUSHDB`) so occupancy does not leak between tests.

After the class: close Lettuce, then `container stop <name>`. `--rm` deletes the container.

That is enough to exercise Lua on a real server. You do not need `valkey-up.sh` for `integrationTest`. You do need the Apple container CLI and a running apiserver (`container system start`).

## Why not Testcontainers Java?

[Testcontainers for Java](https://java.testcontainers.org/supported_docker_environment/) needs a **Docker Engine API** (HTTP to a `docker.sock`). Officially that is Docker Desktop, Docker Engine, Testcontainers Cloud, or Testcontainers Desktop’s embedded runtime. Podman, Colima, and Rancher Desktop work only because they expose a Docker-compatible socket (`DOCKER_HOST`).

Apple’s [`container`](https://github.com/apple/container) CLI is a different API (XPC / its own CLI). There is no `docker.sock`. Testcontainers never sees a runtime.

A request to add Apple Container was closed as not planned: [testcontainers-java#10710](https://github.com/testcontainers/testcontainers-java/issues/10710). The Node library fails the same way (“no working container runtime strategy”).

That is why this module runs `container run …` itself.

## Research: OSS that works with Apple `container`

There is **no** maintained Java Testcontainers equivalent that talks to `container` the way Testcontainers talks to Docker. Options fall into three buckets.

### 1. Testcontainers-like libraries (not Java)

These drive Apple `container` natively (CLI or XPC). They do not help this Gradle/JUnit module without rewriting tests in another language.

| Library | Language | How it talks to `container` |
|---|---|---|
| [shiguredo/container-rs](https://github.com/shiguredo/container-rs) | Rust | XPC to Apple Container on macOS (their main target). Docker Engine API on Linux. Built because they do not expect Testcontainers to add Apple support. |
| [lynicis/applecontainer-go](https://github.com/lynicis/applecontainer-go) | Go | testcontainers-go-shaped API; shells out to the `container` CLI. Same idea as this repo’s JUnit extension. |
| [Mongey/swift-test-containers](https://github.com/Mongey/swift-test-containers) | Swift | Docker **or** Apple `container` as the runtime (`TESTCONTAINERS_RUNTIME=apple`). |

### 2. Docker-API shim, then existing Testcontainers Java

[Socktainer](https://github.com/socktainer/socktainer) is a daemon that exposes a (partial) Docker Engine REST API on a Unix socket, backed by Apple Container:

```text
export DOCKER_HOST=unix://$HOME/.socktainer/container.sock
TESTCONTAINERS_RYUK_DISABLED=true ./gradlew integrationTest
```

They publish a [Java sample](https://github.com/socktainer/sample-testcontainers-java). Ryuk (Testcontainers’ reaper sidecar) is the fragile part; people usually disable it. Socktainer is still evolving. This module does **not** use it.

[acgo](https://github.com/memohai/acgo) is a Go client for that same Socktainer socket, not a JUnit library.

### 3. CLI translators (not test libraries)

These help scripts that expect a `docker` binary. They do not replace JUnit lifecycle, wait strategies, or Ryuk-style cleanup.

| Project | Role |
|---|---|
| [docker-for-apple-container](https://github.com/appautomaton/docker-for-apple-container) | `docker` CLI → `container` CLI translator |
| [apple-compose](https://github.com/Rhevin/apple-compose) | Compose files on Apple Container |
| [podman-desktop/extension-apple-container](https://github.com/podman-desktop/extension-apple-container) | UI; uses Socktainer under the hood |

`io.01def:disposables` (Java) is a Testcontainers-like library that shells out to **Docker or Podman** CLI, not Apple `container`.

## What that means here

This repo’s `AppleContainerValkey` extension is the Java analogue of applecontainer-go: resolve the CLI, `container run`, wait for ready, stop after. There is not a drop-in Java library that replaces that with Testcontainers modules.

Practical choices if this grows:

1. Keep the custom JUnit extension (current).
2. Run Socktainer on developer Macs and switch to Testcontainers Java (Linux CI can keep using Docker). Accept Ryuk disabled and a partial Docker API.
3. Wait for Testcontainers Java itself — not currently planned.

None of those change the occupancy API. They only change how integration tests obtain a Valkey process.
