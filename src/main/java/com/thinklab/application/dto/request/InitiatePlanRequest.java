package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * DTO for Plan creation (BIAN Behavior Qualifier: {@code initiate}). {@code entitlements} maps a feature name to its limit:
 * {@code -1} unlimited, {@code 0} not included, a positive number is the allowed quantity.
 */
@Serdeable
public record InitiatePlanRequest(

        @NotBlank(message = "Plan code is required")
        @Size(max = 32, message = "Plan code must not exceed 32 characters")
        String code,

        @NotBlank(message = "Plan name is required")
        @Size(max = 160, message = "Plan name must not exceed 160 characters")
        String name,

        @NotNull(message = "Entitlements are required (an empty object is allowed)")
        Map<String, Long> entitlements
) {}
