package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/** DTO for Subscription output payload (Subscription Billing Control Record). */
@Serdeable
public record SubscriptionResponse(
        UUID id,
        UUID organisationId,
        String planCode,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
