package org.example.reservation.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ValkeySettingsTest {

    @Test
    void parseRedisUri() {
        ValkeySettings settings = ValkeySettings.parse("redis://127.0.0.1:6379");
        assertEquals("redis://127.0.0.1:6379", settings.uri());
        assertEquals(ValkeySettings.DEFAULT_TIMEOUT, settings.commandTimeout());
        assertEquals("", settings.namespace());
    }

    @Test
    void parseRedissUri() {
        ValkeySettings settings = ValkeySettings.parse("rediss://:secret@valkey.example:6379");
        assertEquals("rediss://:secret@valkey.example:6379", settings.uri());
    }

    @Test
    void withTimeoutAndNamespace() {
        ValkeySettings settings = ValkeySettings.parse("redis://localhost:6379")
                .withTimeout(Duration.ofSeconds(2))
                .withNamespace("staging");
        assertEquals(Duration.ofSeconds(2), settings.commandTimeout());
        assertEquals("staging", settings.namespace());
    }

    @Test
    void rejectsBlankUri() {
        assertThrows(IllegalArgumentException.class,
                () -> new ValkeySettings("  ", Duration.ofSeconds(1), ""));
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> ValkeySettings.parse("redis://localhost:6379").withTimeout(Duration.ZERO));
    }

    @Test
    void rejectsHashTagBracesInNamespace() {
        assertThrows(IllegalArgumentException.class,
                () -> ValkeySettings.parse("redis://localhost:6379").withNamespace("x{y}"));
    }

    @Test
    void rejectsMalformedUri() {
        assertThrows(IllegalArgumentException.class, () -> ValkeySettings.parse("not-a-uri"));
    }
}
