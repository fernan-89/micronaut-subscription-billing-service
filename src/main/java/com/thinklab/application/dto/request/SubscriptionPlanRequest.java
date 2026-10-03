package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO naming the plan a subscription starts on ({@code initiate}) or moves to ({@code plan/update}). organisationId travels
 * via the {@code X-Tenant-Id} header, not the body.
 */
@Serdeable
public record SubscriptionPlanRequest(

        @NotBlank(message = "Plan code is required")
        @Size(max = 32, message = "Plan code must not exceed 32 characters")
        String planCode
) {}
