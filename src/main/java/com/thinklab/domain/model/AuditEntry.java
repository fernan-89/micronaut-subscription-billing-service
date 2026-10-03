package com.thinklab.domain.model;

import java.time.Instant;

/**
 * Immutable forensic ledger entry shared by the Plan and Subscription aggregates.
 *
 * @param fromStatus status name before the action ({@code null} for the initiating entry)
 * @param toStatus   status name after the action (equal to {@code fromStatus} for non-transition actions)
 */
public record AuditEntry(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {}
