package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** DTO for Plan output payload. Prevents the pure Domain Model from bleeding out to the HTTP boundary. */
@Serdeable
public record PlanResponse(
        UUID id,
        String code,
        String name,
        Map<String, Long> entitlements,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
