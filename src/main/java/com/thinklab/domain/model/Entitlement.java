package com.thinklab.domain.model;

import java.util.Map;

/**
 * What a plan says about one feature (ADR-030).
 *
 * <p>A plan lists {@code feature -> limit}: absent or {@code 0} means the feature is not part of the plan, {@code -1} means
 * unlimited, any positive number is the allowed quantity (assets, sites, seats...). A pure on/off feature is simply {@code 1}.
 *
 * @param limit the allowed quantity, {@code null} when the feature is unlimited or not included
 */
public record Entitlement(boolean allowed, Long limit) {

    public static final long UNLIMITED = -1L;

    public static Entitlement of(Map<String, Long> entitlements, String feature) {
        long value = entitlements.getOrDefault(feature, 0L);
        if (value == 0L) {
            return new Entitlement(false, null);
        }
        return new Entitlement(true, value == UNLIMITED ? null : value);
    }
}
