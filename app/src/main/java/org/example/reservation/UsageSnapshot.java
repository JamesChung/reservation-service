package org.example.reservation;

import java.util.Map;
import java.util.Objects;

/**
 * Point-in-time occupancy of a scope. Includes every name in the current quota
 * (used may be 0) and any names still held that are no longer in the quota.
 */
public record UsageSnapshot(Scope scope, Map<ResourceName, ResourceUsage> resources) {

    public UsageSnapshot {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(resources, "resources");
        resources = Map.copyOf(resources);
    }

    public ResourceUsage of(ResourceName name) {
        return resources.getOrDefault(name, new ResourceUsage(0, 0));
    }
}
