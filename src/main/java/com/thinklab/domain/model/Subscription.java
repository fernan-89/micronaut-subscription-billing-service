package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidSubscriptionStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Core Domain Model representing the Subscription Aggregate Root (Control Record) of the {@code subscription-billing}
 * Service Domain: the plan one organisation is on and where it stands (ADR-030, ADR-031).
 *
 * <pre>
 * TRIALING -> ACTIVE
 * ACTIVE -> PAST_DUE | SUSPENDED
 * PAST_DUE -> ACTIVE | SUSPENDED
 * SUSPENDED -> ACTIVE
 * any non-CANCELLED -> CANCELLED (terminal)
 * </pre>
 *
 * <p>An organisation has at most one non-cancelled subscription (the repository's partial unique index enforces it). The plan
 * can change while the subscription is not cancelled; whether the new plan may be chosen is the use case's call, since the
 * aggregate cannot see the plan catalogue.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public class Subscription {

    /** Statuses in which the subscription still counts as the organisation's one subscription. */
    public static final Set<SubscriptionStatus> CURRENT = EnumSet.of(SubscriptionStatus.TRIALING, SubscriptionStatus.ACTIVE,
            SubscriptionStatus.PAST_DUE, SubscriptionStatus.SUSPENDED);

    private final UUID id;
    private final UUID organisationId;
    private String planCode;
    private SubscriptionStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<AuditEntry> auditTrail;

    private Subscription(UUID id, UUID organisationId, String planCode, SubscriptionStatus status,
                         Instant createdAt, Instant updatedAt, List<AuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.planCode = planCode;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.auditTrail = auditTrail;
    }

    /** Static factory for aggregate creation (BIAN Behavior Qualifier: {@code initiate}). Starts in {@code TRIALING}. */
    public static Subscription createNew(UUID id, UUID organisationId, String planCode, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for Subscription creation.");
        }
        requirePlanCode(planCode);
        requireExecutor(executor);
        Instant now = Instant.now();
        Subscription subscription = new Subscription(id, organisationId, planCode, SubscriptionStatus.TRIALING, now, now, new ArrayList<>());
        subscription.auditTrail.add(new AuditEntry(now, "INITIATED", executor, null, SubscriptionStatus.TRIALING.name(),
                "Subscribed to plan " + planCode + "."));
        return subscription;
    }

    /** Reconstitutes an existing Subscription aggregate from the persistence layer. */
    public static Subscription reconstitute(UUID id, UUID organisationId, String planCode, SubscriptionStatus status,
                                            Instant createdAt, Instant updatedAt, List<AuditEntry> auditTrail) {
        if (id == null || organisationId == null || planCode == null || status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Every field except the audit trail is mandatory to reconstitute a Subscription.");
        }
        return new Subscription(id, organisationId, planCode, status, createdAt, updatedAt,
                auditTrail == null ? new ArrayList<>() : new ArrayList<>(auditTrail));
    }

    /** Behavior Qualifier: {@code control/activate}. {@code TRIALING|PAST_DUE|SUSPENDED -> ACTIVE}. */
    public AuditEntry activate(String executor) {
        return moveFrom(EnumSet.of(SubscriptionStatus.TRIALING, SubscriptionStatus.PAST_DUE, SubscriptionStatus.SUSPENDED),
                SubscriptionStatus.ACTIVE, executor, "Activated.");
    }

    /** Behavior Qualifier: {@code control/mark-past-due}. {@code ACTIVE -> PAST_DUE}. */
    public AuditEntry markPastDue(String executor) {
        return moveFrom(EnumSet.of(SubscriptionStatus.ACTIVE), SubscriptionStatus.PAST_DUE, executor, "Marked past due.");
    }

    /** Behavior Qualifier: {@code control/suspend}. {@code ACTIVE|PAST_DUE -> SUSPENDED}. */
    public AuditEntry suspend(String executor) {
        return moveFrom(EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE), SubscriptionStatus.SUSPENDED, executor, "Suspended.");
    }

    /** Behavior Qualifier: {@code control/cancel}. Any non-cancelled status {@code -> CANCELLED} (terminal). */
    public AuditEntry cancel(String executor) {
        return moveFrom(CURRENT, SubscriptionStatus.CANCELLED, executor, "Cancelled.");
    }

    /** Behavior Qualifier: {@code plan/update}. Moves the subscription to another plan; illegal once cancelled. */
    public AuditEntry changePlan(String newPlanCode, String executor) {
        requireExecutor(executor);
        requirePlanCode(newPlanCode);
        if (!isCurrent()) {
            throw new InvalidSubscriptionStatusException("Compliance Violation: a CANCELLED subscription cannot change plan.");
        }
        if (newPlanCode.equals(planCode)) {
            throw new InvalidSubscriptionStatusException("The subscription is already on plan " + planCode + ".");
        }
        String previous = this.planCode;
        this.planCode = newPlanCode;
        this.updatedAt = Instant.now();
        AuditEntry entry = new AuditEntry(updatedAt, "PLAN_CHANGED", executor, status.name(), status.name(),
                "Plan changed from " + previous + " to " + newPlanCode + ".");
        auditTrail.add(entry);
        return entry;
    }

    private AuditEntry moveFrom(Set<SubscriptionStatus> allowed, SubscriptionStatus target, String executor, String detail) {
        requireExecutor(executor);
        if (!allowed.contains(status)) {
            throw new InvalidSubscriptionStatusException(String.format(
                    "Compliance Violation: cannot move a Subscription from [%s] to [%s]; allowed origins are %s.", status, target, allowed));
        }
        SubscriptionStatus previous = this.status;
        this.status = target;
        this.updatedAt = Instant.now();
        AuditEntry entry = new AuditEntry(updatedAt, "STATUS_CHANGED", executor, previous.name(), target.name(), detail);
        auditTrail.add(entry);
        return entry;
    }

    private static void requirePlanCode(String planCode) {
        if (planCode == null || planCode.isBlank()) {
            throw new IllegalArgumentException("Plan code is mandatory for a Subscription.");
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable Subscription mutations.");
        }
    }

    public boolean isCurrent() { return CURRENT.contains(status); }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getPlanCode() { return planCode; }
    public SubscriptionStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<AuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    public enum SubscriptionStatus { TRIALING, ACTIVE, PAST_DUE, SUSPENDED, CANCELLED }
}
