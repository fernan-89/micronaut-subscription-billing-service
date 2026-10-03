package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Answer to "may this organisation use this feature?" (ADR-032).
 *
 * @param source {@code SUBSCRIPTION} (the organisation's own plan), {@code DEFAULT_PLAN} (no subscription: the default plan
 *               stands in), {@code SUSPENDED} (its subscription is suspended: nothing is allowed) or {@code UNMANAGED} (no
 *               plan is configured to decide: allowed, fail-open)
 * @param limit  the allowed quantity, {@code null} when unlimited or not allowed
 */
@Serdeable
public record EntitlementResponse(
        String feature,
        boolean allowed,
        @Nullable Long limit,
        String source,
        @Nullable String planCode
) {}
