package org.example.reservation.sample;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.UUID;
import org.example.reservation.Denied;
import org.example.reservation.DenialReason;
import org.example.reservation.Owner;
import org.example.reservation.Reservation;
import org.example.reservation.ReservationId;
import org.example.reservation.ReservationRetries;
import org.example.reservation.ReservationStore;
import org.example.reservation.ReserveRequest;
import org.example.reservation.ResourceName;
import org.example.reservation.ResourceShortage;
import org.example.reservation.ResourceUsage;
import org.example.reservation.ResourceVector;
import org.example.reservation.Scope;
import org.example.reservation.Try;
import org.example.reservation.UsageSnapshot;
import org.example.reservation.memory.InMemoryReservationStore;
import org.example.reservation.valkey.ValkeyReservationStore;
import org.example.reservation.valkey.ValkeySettings;

/**
 * Runnable walk-through of the CI mapping in {@code docs/samples/ci-pipelines.md}.
 *
 * <pre>
 *   ./gradlew runPipelineSample
 *   ./gradlew runPipelineSample --args='--valkey redis://127.0.0.1:6379'
 * </pre>
 */
public final class PipelineSimulation {

    static final ResourceName RUNS = new ResourceName("pipeline.runs");
    static final ResourceName CPU = new ResourceName("cpu.millis");
    static final ResourceName MEMORY = new ResourceName("memory.bytes");
    static final ResourceName DISK = new ResourceName("disk.bytes");

    static final String TEAM = "acme";
    static final Scope JENKINS = new Scope("pipelinesv1", TEAM);
    static final Scope K8S = new Scope("pipelinesv2", TEAM);
    static final Owner WORKER = new Owner("worker-1");
    static final Duration TTL = Duration.ofSeconds(10);
    static final Duration WORK = Duration.ofMillis(300);

    private final ReservationStore store;
    private final ReservationRetries retries = new ReservationRetries();

    PipelineSimulation(ReservationStore store) {
        this.store = store;
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help) {
            System.out.println(
                    """
                    Simulate pipelinesv1 (Jenkins) and pipelinesv2 (K8s) occupancy.

                      ./gradlew runPipelineSample
                      ./gradlew runPipelineSample --args='--valkey redis://127.0.0.1:6379'

                    In-memory by default. --valkey uses ValkeyReservationStore (namespace ci-sample).
                    """);
            return;
        }
        ReservationStore store = options.valkeyUri == null
                ? new InMemoryReservationStore()
                : ValkeyReservationStore.connect(
                        ValkeySettings.parse(options.valkeyUri).withNamespace("ci-sample"));
        int code = 0;
        try {
            new PipelineSimulation(store).run();
        } catch (RuntimeException e) {
            System.err.println("SIMULATION FAILED: " + e.getMessage());
            e.printStackTrace(System.err);
            code = 1;
        } finally {
            if (store instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
        System.exit(code);
    }

    void run() throws InterruptedException {
        String v1Build = UUID.randomUUID().toString();
        String v1Release = UUID.randomUUID().toString();
        String v2Test = UUID.randomUUID().toString();
        String v2Deploy = UUID.randomUUID().toString();

        log("=== CI catalog (not stored in ReservationStore) ===");
        log("team=%s  project=github.com/acme/shop", TEAM);
        log("pipelinesv1 definitions:  build=%s  release=%s", v1Build, v1Release);
        log("pipelinesv2 definitions:  test=%s  deploy=%s", v2Test, v2Deploy);
        log("ReservationId is always a *run* UUID, never a definition UUID.");
        log("");

        expectOk("replace v1 quotas", store.replaceQuotas(JENKINS, ResourceVector.of(RUNS, 2)));
        expectOk(
                "replace v2 quotas",
                store.replaceQuotas(
                        K8S,
                        ResourceVector.builder()
                                .put(RUNS, 2)
                                .put(CPU, 1_000)
                                .put(MEMORY, 2L << 30)
                                .put(DISK, 20L << 30)
                                .build()));
        drain(JENKINS);
        drain(K8S);
        log("quotas: v1 pipeline.runs=2; v2 pipeline.runs=2 cpu.millis=1000 memory=2Gi disk=20Gi");
        printUsage("after quotas");

        log("");
        log("=== 1. Two Jenkins runs fill the v1 pool ===");
        Reservation jenkinsA = admitV1("build", v1Build);
        Reservation jenkinsB = admitV1("release", v1Release);
        printUsage("v1 full");

        log("");
        log("=== 2. Third Jenkins run is denied (pool full) ===");
        String parkedRun = UUID.randomUUID().toString();
        ReserveRequest parked = v1Request(parkedRun);
        expectDenied("[v1] run=" + parkedRun + " def=" + v1Build, store.tryReserve(parked), DenialReason.INSUFFICIENT_CAPACITY);

        log("");
        log("=== 3. Two K8s runs still admit — v2 is a different scope ===");
        Reservation k8sA = admitV2("test", v2Test, 500);
        Reservation k8sB = admitV2("deploy", v2Deploy, 500);
        printUsage("v1 full, v2 full");

        log("");
        log("=== 4. Third K8s run denied on occupancy; oversized CPU is a hard deny ===");
        expectDenied(
                "[v2] extra run",
                store.tryReserve(v2Request(UUID.randomUUID().toString(), 100)),
                DenialReason.INSUFFICIENT_CAPACITY);
        expectDenied(
                "[v2] cpu.millis=2000",
                store.tryReserve(v2Request(UUID.randomUUID().toString(), 2_000)),
                DenialReason.REQUEST_EXCEEDS_QUOTA);

        log("");
        log("=== 5. Finish one Jenkins run, then ReservationRetries admits the parked v1 run ===");
        finish("v1", "build", v1Build, jenkinsA);
        printUsage("after one Jenkins release");
        Try<Reservation> waited = retries.reserve(store, parked, Duration.ofSeconds(2));
        Reservation jenkinsC = expectGrant("[v1] run=" + parkedRun + " def=" + v1Build + " (retried)", waited);
        claim(jenkinsC);

        log("");
        log("=== 6. Drain remaining runs ===");
        finish("v1", "release", v1Release, jenkinsB);
        finish("v1", "build", v1Build, jenkinsC);
        finish("v2", "test", v2Test, k8sA);
        finish("v2", "deploy", v2Deploy, k8sB);
        printUsage("idle");
        expectIdle(JENKINS);
        expectIdle(K8S);
        log("");
        log("Done. Jenkins occupancy never consumed K8s CPU; definition UUIDs were never ReservationIds.");
    }

    private Reservation admitV1(String defName, String definitionUuid) {
        String run = UUID.randomUUID().toString();
        Reservation hold = expectGrant(
                "[v1] run=" + run + " def=" + definitionUuid + " (" + defName + ")",
                store.tryReserve(v1Request(run)));
        claim(hold);
        return hold;
    }

    private Reservation admitV2(String defName, String definitionUuid, long cpuMillis) {
        String run = UUID.randomUUID().toString();
        Reservation hold = expectGrant(
                "[v2] run=" + run + " def=" + definitionUuid + " (" + defName + ") cpu.millis=" + cpuMillis,
                store.tryReserve(v2Request(run, cpuMillis)));
        claim(hold);
        return hold;
    }

    private ReserveRequest v1Request(String runUuid) {
        return new ReserveRequest(
                new ReservationId(runUuid), WORKER, JENKINS, ResourceVector.of(RUNS, 1), TTL);
    }

    private ReserveRequest v2Request(String runUuid, long cpuMillis) {
        return new ReserveRequest(
                new ReservationId(runUuid),
                WORKER,
                K8S,
                ResourceVector.builder()
                        .put(RUNS, 1)
                        .put(CPU, cpuMillis)
                        .put(MEMORY, 1L << 30)
                        .put(DISK, 1L << 30)
                        .build(),
                TTL);
    }

    private void claim(Reservation hold) {
        if (!hold.owner().equals(WORKER)) {
            throw new IllegalStateException("owner mismatch: " + hold.owner().value());
        }
    }

    private void finish(String arch, String defName, String definitionUuid, Reservation hold) throws InterruptedException {
        log(
                "[%s] run=%s def=%s (%s) WORK %dms",
                arch, hold.id().value(), definitionUuid, defName, WORK.toMillis());
        Thread.sleep(WORK.toMillis());
        Try<Reservation> extended = store.extend(hold, TTL);
        if (!extended.isSuccess()) {
            throw new IllegalStateException("extend failed: " + extended.cause());
        }
        Reservation current = extended.get();
        Try<Boolean> released = store.release(current);
        if (!released.isSuccess() || !Boolean.TRUE.equals(released.get())) {
            throw new IllegalStateException("release did not drop hold " + hold.id().value());
        }
        log("[%s] run=%s RELEASED", arch, hold.id().value());
    }

    private void drain(Scope scope) {
        Try<List<Reservation>> listed = store.list(scope);
        if (!listed.isSuccess()) {
            return;
        }
        for (Reservation leftover : listed.get()) {
            store.release(leftover);
            log("drained leftover %s %s", scope.type(), leftover.id().value());
        }
    }

    private void printUsage(String when) {
        log("usage (%s)", when);
        log("  %s", formatUsage(JENKINS));
        log("  %s", formatUsage(K8S));
    }

    private String formatUsage(Scope scope) {
        Try<UsageSnapshot> result = store.usage(scope);
        if (!result.isSuccess()) {
            return scope.type() + " <unavailable: " + result.cause() + ">";
        }
        UsageSnapshot snap = result.get();
        StringJoiner joiner = new StringJoiner(" ");
        snap.resources().forEach((name, usage) -> joiner.add(format(name, usage)));
        return scope.type() + " " + joiner;
    }

    private static String format(ResourceName name, ResourceUsage usage) {
        if (name.equals(MEMORY) || name.equals(DISK)) {
            return name.value() + "=" + gi(usage.used()) + "/" + gi(usage.limit()) + "Gi";
        }
        return name.value() + "=" + usage.used() + "/" + usage.limit();
    }

    private static String gi(long bytes) {
        return Long.toString(bytes >> 30);
    }

    private void expectIdle(Scope scope) {
        Try<UsageSnapshot> result = store.usage(scope);
        if (!result.isSuccess()) {
            throw new IllegalStateException("usage failed: " + result.cause());
        }
        for (ResourceUsage usage : result.get().resources().values()) {
            if (usage.used() != 0) {
                throw new IllegalStateException(scope.type() + " still occupied: " + formatUsage(scope));
            }
        }
    }

    private static void expectOk(String what, Try<Void> result) {
        if (!result.isSuccess()) {
            throw new IllegalStateException(what + " failed: " + result.cause());
        }
    }

    private static Reservation expectGrant(String label, Try<Reservation> result) {
        if (!result.isSuccess()) {
            throw new IllegalStateException(label + " expected grant, got " + describe(result));
        }
        Reservation hold = result.get();
        log("%s ADMITTED token=%s owner=%s", label, hold.token().value(), hold.owner().value());
        return hold;
    }

    private static void expectDenied(String label, Try<Reservation> result, DenialReason reason) {
        if (result.isSuccess()) {
            throw new IllegalStateException(label + " expected " + reason + ", granted " + result.get().id().value());
        }
        Throwable cause = result.cause();
        if (!(cause instanceof Denied denied) || denied.reason() != reason) {
            throw new IllegalStateException(label + " expected " + reason + ", got " + describe(result));
        }
        StringJoiner shortages = new StringJoiner("; ");
        for (ResourceShortage s : denied.shortages()) {
            shortages.add(s.name().value() + " requested=" + s.requested() + " used=" + s.used() + " limit=" + s.limit());
        }
        log("%s DENIED %s %s", label, reason, shortages);
    }

    private static String describe(Try<Reservation> result) {
        if (result.isSuccess()) {
            return "grant " + result.get().id().value();
        }
        Throwable cause = result.cause();
        if (cause instanceof Denied denied) {
            return denied.reason().name();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static void log(String format, Object... args) {
        System.out.printf(Locale.ROOT, format + "%n", args);
    }

    private record Options(boolean help, String valkeyUri) {

        static Options parse(String[] args) {
            boolean help = false;
            String valkeyUri = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                } else if ("--valkey".equals(arg)) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--valkey requires a redis:// or rediss:// URI");
                    }
                    valkeyUri = args[++i];
                } else {
                    throw new IllegalArgumentException("unknown argument: " + arg + " (try --help)");
                }
            }
            return new Options(help, valkeyUri);
        }
    }
}
