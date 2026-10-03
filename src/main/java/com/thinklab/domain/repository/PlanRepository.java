package com.thinklab.domain.repository;

import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Plan;
import com.thinklab.domain.model.Plan.PlanStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Outbound Port for Plan persistence (Subscription Billing Service Domain). Every state change is a granular update that
 * appends its forensic {@link AuditEntry} atomically (ADR-002); there is no physical delete.
 */
public interface PlanRepository {

    /** Fails with {@code DuplicatePlanException} when the plan code is already taken. */
    Mono<Plan> create(Plan plan);

    Mono<Plan> findById(UUID id);

    Mono<Plan> findByCode(String code);

    /** All plans, optionally only those in one status. */
    Flux<Plan> findAll(PlanStatus status);

    Mono<Void> updateContent(UUID id, String name, Map<String, Long> entitlements, AuditEntry auditEntry);

    Mono<Void> updateStatus(UUID id, PlanStatus status, AuditEntry auditEntry);
}
