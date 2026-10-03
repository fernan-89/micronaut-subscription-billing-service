package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidPlanStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Core Domain Model representing the Plan aggregate of the {@code subscription-billing} Service Domain: a named edition
 * (HOMELAB, TEAM, ENTERPRISE...) and the entitlements it grants (ADR-030). Plans are platform-wide, not per tenant: every
 * organisation's subscription points at one by its {@code code}.
 *
 * <p>Lifecycle (ADR-031): {@code DRAFT -> ACTIVE -> RETIRED}. Entitlements are only editable in {@code DRAFT} - an
 * {@code ACTIVE} plan is a contract tenants are subscribed to right now. {@code RETIRED} plans keep serving the
 * subscriptions already on them but cannot be chosen for a new one.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public class Plan {

    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    static final Pattern FEATURE = Pattern.compile("[a-z][a-z0-9._-]{0,63}");

    private final UUID id;
    private final String code;
    private String name;
    private Map<String, Long> entitlements;
    private PlanStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<AuditEntry> auditTrail;

    private Plan(UUID id, String code, String name, Map<String, Long> entitlements, PlanStatus status,
                 Instant createdAt, Instant updatedAt, List<AuditEntry> auditTrail) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.entitlements = entitlements;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.auditTrail = auditTrail;
    }

    /** Static factory for aggregate creation (BIAN Behavior Qualifier: {@code initiate}). */
    public static Plan createNew(UUID id, String code, String name, Map<String, Long> entitlements, String executor) {
        if (id == null) {
            throw new IllegalArgumentException("ID is mandatory for Plan creation.");
        }
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Plan code must be 2-32 characters: an upper-case letter, then upper-case letters, digits or underscores.");
        }
        requireExecutor(executor);
        Instant now = Instant.now();
        Plan plan = new Plan(id, code, validName(name), validEntitlements(entitlements), PlanStatus.DRAFT, now, now, new ArrayList<>());
        plan.auditTrail.add(new AuditEntry(now, "INITIATED", executor, null, PlanStatus.DRAFT.name(), "Plan drafted."));
        return plan;
    }

    /** Reconstitutes an existing Plan aggregate from the persistence layer. */
    public static Plan reconstitute(UUID id, String code, String name, Map<String, Long> entitlements, PlanStatus status,
                                    Instant createdAt, Instant updatedAt, List<AuditEntry> auditTrail) {
        if (id == null || code == null || name == null || entitlements == null || status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Every field except the audit trail is mandatory to reconstitute a Plan.");
        }
        return new Plan(id, code, name, new LinkedHashMap<>(entitlements), status, createdAt, updatedAt,
                auditTrail == null ? new ArrayList<>() : new ArrayList<>(auditTrail));
    }

    /** Behavior Qualifier: {@code update}. Replaces name and entitlements; only legal in {@code DRAFT}. */
    public AuditEntry updateContent(String newName, Map<String, Long> newEntitlements, String executor) {
        requireExecutor(executor);
        if (status != PlanStatus.DRAFT) {
            throw new InvalidPlanStatusException(String.format(
                    "Compliance Violation: cannot edit a Plan in [%s] state; only DRAFT plans are editable.", status));
        }
        this.name = validName(newName);
        this.entitlements = validEntitlements(newEntitlements);
        this.updatedAt = Instant.now();
        AuditEntry entry = new AuditEntry(updatedAt, "UPDATED", executor, status.name(), status.name(), "Name and entitlements updated.");
        auditTrail.add(entry);
        return entry;
    }

    /** Behavior Qualifier: {@code control/activate}. {@code DRAFT -> ACTIVE}. */
    public AuditEntry activate(String executor) {
        return transition(PlanStatus.DRAFT, PlanStatus.ACTIVE, executor, "Activated.");
    }

    /** Behavior Qualifier: {@code control/retire}. {@code ACTIVE -> RETIRED} (terminal). */
    public AuditEntry retire(String executor) {
        return transition(PlanStatus.ACTIVE, PlanStatus.RETIRED, executor, "Retired.");
    }

    public Entitlement entitlement(String feature) {
        return Entitlement.of(entitlements, feature);
    }

    private AuditEntry transition(PlanStatus required, PlanStatus target, String executor, String detail) {
        requireExecutor(executor);
        if (status != required) {
            throw new InvalidPlanStatusException(String.format(
                    "Compliance Violation: cannot move a Plan from [%s] to [%s]; only %s can.", status, target, required));
        }
        this.status = target;
        this.updatedAt = Instant.now();
        AuditEntry entry = new AuditEntry(updatedAt, "STATUS_CHANGED", executor, required.name(), target.name(), detail);
        auditTrail.add(entry);
        return entry;
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Plan name is mandatory.");
        }
        return name.trim();
    }

    private static Map<String, Long> validEntitlements(Map<String, Long> entitlements) {
        if (entitlements == null) {
            throw new IllegalArgumentException("Plan entitlements are mandatory (an empty map is allowed).");
        }
        Map<String, Long> copy = new LinkedHashMap<>();
        entitlements.forEach((feature, limit) -> {
            if (feature == null || !FEATURE.matcher(feature).matches()) {
                throw new IllegalArgumentException("Entitlement feature names are lower-case, 1-64 characters of letters, digits, '.', '_' or '-': " + feature);
            }
            if (limit == null || limit < Entitlement.UNLIMITED) {
                throw new IllegalArgumentException("Entitlement limit for [" + feature + "] must be -1 (unlimited), 0 (not included) or a positive number.");
            }
            copy.put(feature, limit);
        });
        return copy;
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable Plan mutations.");
        }
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public Map<String, Long> getEntitlements() { return Collections.unmodifiableMap(entitlements); }
    public PlanStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<AuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    /**
     * <pre>
     * DRAFT -> ACTIVE
     * ACTIVE -> RETIRED
     * </pre>
     */
    public enum PlanStatus { DRAFT, ACTIVE, RETIRED }
}
