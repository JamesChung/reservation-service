package org.example.reservation.valkey;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;

/**
 * Starts {@code valkey/valkey:8} with Apple {@code container} and exposes a
 * {@link ValkeyReservationStore}. Skips the test class if the CLI/apiserver is unavailable.
 */
public final class AppleContainerValkey implements BeforeAllCallback, AfterAllCallback {

    static final String IMAGE = "valkey/valkey:8";

    private static final List<String> BINARY_CANDIDATES = List.of(
            "container",
            "/opt/homebrew/bin/container",
            "/usr/local/bin/container");

    private String binary;
    private String containerName;
    private int port;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private ValkeyReservationStore store;

    ValkeyReservationStore store() {
        return store;
    }

    RedisCommands<String, String> commands() {
        return connection.sync();
    }

    String redisUri() {
        return "redis://127.0.0.1:" + port;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        binary = resolveBinary();
        if (binary == null || !apiserverRunning(binary)) {
            throw new TestAbortedException(
                    "Apple container CLI/apiserver not available; skipping Valkey integration tests");
        }
        port = freePort();
        containerName = "reservation-valkey-" + UUID.randomUUID();
        run(List.of(
                binary, "run", "-d", "--rm",
                "--name", containerName,
                "-p", "127.0.0.1:" + port + ":6379",
                IMAGE), Duration.ofMinutes(2));
        RedisURI uri = RedisURI.builder().withHost("127.0.0.1").withPort(port).withTimeout(Duration.ofSeconds(5)).build();
        client = RedisClient.create(uri);
        waitForPing(Duration.ofSeconds(60));
        connection = client.connect();
        store = new ValkeyReservationStore(connection.sync());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
        if (binary != null && containerName != null) {
            try {
                run(List.of(binary, "stop", containerName), Duration.ofSeconds(30));
            } catch (Exception ignored) {
                // container may already be gone (--rm)
            }
        }
    }

    private void waitForPing(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try (StatefulRedisConnection<String, String> probe = client.connect()) {
                if ("PONG".equalsIgnoreCase(probe.sync().ping())) {
                    return;
                }
            } catch (RuntimeException e) {
                last = e;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        throw new IllegalStateException("Valkey did not become ready", last);
    }

    private static String resolveBinary() {
        for (String candidate : BINARY_CANDIDATES) {
            Path path = Path.of(candidate);
            if (path.isAbsolute()) {
                if (Files.isExecutable(path)) {
                    return path.toString();
                }
            } else if (which(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean which(String name) {
        try {
            Process process = new ProcessBuilder("which", name).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean apiserverRunning(String binary) {
        try {
            Process process = new ProcessBuilder(binary, "system", "status")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 && output.toLowerCase().contains("running");
        } catch (Exception e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static void run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("timed out running " + command);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(command + " failed (" + process.exitValue() + "): " + output);
        }
    }
}
