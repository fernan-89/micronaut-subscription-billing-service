package com.thinklab.domain.repository;

import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Subscription;
import com.thinklab.domain.model.Subscription.SubscriptionStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port for Subscription persistence (Subscription Billing Service Domain). Every state change is a granular update
 * that appends its forensic {@link AuditEntry} atomically (ADR-002); there is no physical delete.
 */
public interface SubscriptionRepository {

    /** Fails with {@code DuplicateSubscriptionException} when the organisation already has a non-cancelled subscription. */
    Mono<Subscription> create(Subscription subscription);

    Mono<Subscription> findById(UUID id);

    /** The organisation's one non-cancelled subscription, if any. */
    Mono<Subscription> findCurrentByOrganisationId(UUID organisationId);

    /** The organisation's subscriptions, newest first, optionally only those in one status. */
    Flux<Subscription> findAllByOrganisationId(UUID organisationId, SubscriptionStatus status);

    Mono<Void> updateStatus(UUID id, SubscriptionStatus status, AuditEntry auditEntry);

    Mono<Void> updatePlan(UUID id, String planCode, AuditEntry auditEntry);
}
