package org.example.reservation.valkey;

import java.time.Duration;
import java.util.Objects;
import io.lettuce.core.RedisURI;

/**
 * Connection settings for {@link ValkeyReservationStore#connect(ValkeySettings)}.
 *
 * <p>{@code uri} is a Lettuce/{@link RedisURI} string ({@code redis://} or {@code rediss://}
 * with optional password). {@code namespace} is inserted into the hash tag as
 * {@code {rsv:<namespace>:<type>:<id>}} so multiple apps can share one Valkey; blank
 * keeps {@code {rsv:<type>:<id>}}.
 */
public record ValkeySettings(String uri, Duration commandTimeout, String namespace) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    public ValkeySettings {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(commandTimeout, "commandTimeout");
        Objects.requireNonNull(namespace, "namespace");
        if (uri.isBlank()) {
            throw new IllegalArgumentException("uri must be non-blank");
        }
        if (commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
        if (namespace.contains("{") || namespace.contains("}")) {
            throw new IllegalArgumentException("namespace must not contain '{' or '}'");
        }
        RedisURI.create(uri);
    }

    public static ValkeySettings parse(String uri) {
        return new ValkeySettings(uri, DEFAULT_TIMEOUT, "");
    }

    public ValkeySettings withTimeout(Duration timeout) {
        return new ValkeySettings(uri, timeout, namespace);
    }

    public ValkeySettings withNamespace(String namespace) {
        return new ValkeySettings(uri, commandTimeout, namespace == null ? "" : namespace);
    }

    RedisURI redisUri() {
        RedisURI parsed = RedisURI.create(uri);
        parsed.setTimeout(commandTimeout);
        return parsed;
    }
}
