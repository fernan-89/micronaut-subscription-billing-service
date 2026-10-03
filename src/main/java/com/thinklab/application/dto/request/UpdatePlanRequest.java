package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** DTO for replacing a DRAFT plan's name and entitlements (BIAN Behavior Qualifier: {@code update}). */
@Serdeable
public record UpdatePlanRequest(

        @NotBlank(message = "Plan name is required")
        @Size(max = 160, message = "Plan name must not exceed 160 characters")
        String name,

        @NotNull(message = "Entitlements are required (an empty object is allowed)")
        Map<String, Long> entitlements
) {}
