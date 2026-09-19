package org.example.reservation.valkey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.reservation.Denied;
import org.example.reservation.DenialReason;
import org.example.reservation.LeaseToken;
import org.example.reservation.Owner;
import org.example.reservation.Reservation;
import org.example.reservation.ReservationException;
import org.example.reservation.ReservationId;
import org.example.reservation.ResourceName;
import org.example.reservation.ResourceShortage;
import org.example.reservation.ResourceUsage;
import org.example.reservation.ResourceVector;
import org.example.reservation.Scope;
import org.example.reservation.UsageSnapshot;

final class ReservationCodec {

    private static final TypeReference<List<Hold>> HOLD_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Long>> LONG_MAP = new TypeReference<>() {};

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    String resourcesJson(ResourceVector vector) {
        Map<String, Long> map = new LinkedHashMap<>();
        vector.amounts().forEach((name, amount) -> map.put(name.value(), amount));
        return write(map);
    }

    String quotasJson(ResourceVector quotas) {
        return resourcesJson(quotas);
    }

    Reservation hold(String json) {
        Hold hold = read(json, Hold.class);
        requireReadable(hold);
        return hold.toReservation();
    }

    List<Reservation> holds(String json) {
        try {
            List<Hold> holds = mapper.readValue(json, HOLD_LIST);
            List<Reservation> result = new ArrayList<>(holds.size());
            for (Hold hold : holds) {
                requireReadable(hold);
                result.add(hold.toReservation());
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new ReservationException("invalid hold list JSON", e);
        }
    }

    Denied denied(String json) {
        Denial denial = read(json, Denial.class);
        List<ResourceShortage> shortages = new ArrayList<>();
        if (denial.shortages != null) {
            for (Shortage s : denial.shortages) {
                shortages.add(new ResourceShortage(
                        new ResourceName(s.name), s.requested, s.used, s.limit));
            }
        }
        return new Denied(DenialReason.valueOf(denial.reason), shortages, usage(denial.usage));
    }

    UsageSnapshot usage(String json) {
        return usage(read(json, Usage.class));
    }

    ResourceVector quotas(String json) {
        try {
            Map<String, Long> map = mapper.readValue(json, LONG_MAP);
            ResourceVector.Builder builder = ResourceVector.builder();
            map.forEach((name, amount) -> builder.put(new ResourceName(name), amount));
            return builder.build();
        } catch (JsonProcessingException e) {
            throw new ReservationException("invalid quota JSON", e);
        }
    }

    private UsageSnapshot usage(Usage usage) {
        Map<ResourceName, ResourceUsage> resources = new LinkedHashMap<>();
        if (usage != null && usage.resources != null) {
            usage.resources.forEach((name, ru) ->
                    resources.put(new ResourceName(name), new ResourceUsage(ru.used, ru.limit)));
        }
        Scope scope = usage == null
                ? new Scope("unknown", "unknown")
                : new Scope(usage.scopeType, usage.scopeId);
        return new UsageSnapshot(scope, resources);
    }

    private static void requireReadable(Hold hold) {
        if (!ValkeySchema.readablePayload(hold.v)) {
            throw new ReservationException("hold schema version is newer than this client");
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ReservationException("JSON encode failed", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new ReservationException("invalid JSON: " + json, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Hold {
        public Integer v;
        public String id;
        public String token;
        public String owner;
        public String scopeType;
        public String scopeId;
        public Map<String, Long> resources;
        public long createdAt;
        public long expiresAt;

        Reservation toReservation() {
            ResourceVector.Builder builder = ResourceVector.builder();
            if (resources != null) {
                resources.forEach((name, amount) -> builder.put(new ResourceName(name), amount));
            }
            return new Reservation(
                    new ReservationId(id),
                    new LeaseToken(token),
                    new Owner(owner),
                    new Scope(scopeType, scopeId),
                    builder.build(),
                    Instant.ofEpochMilli(createdAt),
                    Instant.ofEpochMilli(expiresAt));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Denial {
        public String reason;
        public List<Shortage> shortages;
        public Usage usage;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Shortage {
        public String name;
        public long requested;
        public long used;
        public long limit;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Usage {
        public String scopeType;
        public String scopeId;
        public Map<String, UsageEntry> resources;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class UsageEntry {
        public long used;
        public long limit;
    }
}
