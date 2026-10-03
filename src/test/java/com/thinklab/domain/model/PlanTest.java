package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidPlanStatusException;
import com.thinklab.domain.model.Plan.PlanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTest {

    private static final String EXECUTOR = "billing-admin";
    private final UUID id = UUID.randomUUID();

    private Plan draft() {
        return Plan.createNew(id, "TEAM", "  Team edition ", Map.of("assets", 500L, "discovery", 1L, "sites", -1L), EXECUTOR);
    }

    @Test
    @DisplayName("createNew drafts a plan with a trimmed name, its entitlements and an INITIATED audit entry")
    void createNew() {
        Plan plan = draft();

        assertEquals(id, plan.getId());
        assertEquals("TEAM", plan.getCode());
        assertEquals("Team edition", plan.getName());
        assertEquals(PlanStatus.DRAFT, plan.getStatus());
        assertEquals(500L, plan.getEntitlements().get("assets"));
        assertEquals(plan.getCreatedAt(), plan.getUpdatedAt());
        assertEquals(1, plan.getAuditTrail().size());
        AuditEntry first = plan.getAuditTrail().get(0);
        assertEquals("INITIATED", first.action());
        assertNull(first.fromStatus());
        assertEquals("DRAFT", first.toStatus());
        assertEquals(EXECUTOR, first.executor());
    }

    @Test
    @DisplayName("createNew refuses a missing id, a malformed code, a missing executor, a blank name and bad entitlements")
    void createNewGuards() {
        Map<String, Long> ok = Map.of();
        assertThrows(IllegalArgumentException.class, () -> Plan.createNew(null, "TEAM", "n", ok, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Plan.createNew(id, null, "n", ok, EXECUTOR));
        for (String bad : new String[]{"team", "T", "1TEAM", "TEAM-X", "A".repeat(33)}) {
            assertThrows(IllegalArgumentException.class, () -> Plan.createNew(id, bad, "n", ok, EXECUTOR), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Plan.createNew(id, "TEAM", "n", ok, null));
        assertThrows(IllegalArgumentException.class, () -> Plan.createNew(id, "TEAM", "n", ok, " "));
        assertThrows(IllegalArgumentException.class, () -> Plan.createNew(id, "TEAM", null, ok, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Plan.createNew(id, "TEAM", " ", ok, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Plan.createNew(id, "TEAM", "n", null, EXECUTOR));

        Map<String, Long> nullKey = new HashMap<>();
        nullKey.put(null, 1L);
        assertThrows(IllegalArgumentException.class, () -> Plan.createNew(id, "TEAM", "n", nullKey, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Plan.createNew(id, "TEAM", "n", Map.of("Assets", 1L), EXECUTOR));
        Map<String, Long> nullLimit = new HashMap<>();
        nullLimit.put("assets", null);
        assertThrows(IllegalArgumentException.class, () -> Plan.createNew(id, "TEAM", "n", nullLimit, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Plan.createNew(id, "TEAM", "n", Map.of("assets", -2L), EXECUTOR));
    }

    @Test
    @DisplayName("updateContent replaces name and entitlements in DRAFT and records an UPDATED entry")
    void updateContent() {
        Plan plan = draft();

        AuditEntry entry = plan.updateContent("Team 2", Map.of("assets", 900L), "other-admin");

        assertEquals("Team 2", plan.getName());
        assertEquals(Map.of("assets", 900L), plan.getEntitlements());
        assertEquals("UPDATED", entry.action());
        assertEquals("DRAFT", entry.fromStatus());
        assertEquals("DRAFT", entry.toStatus());
        assertEquals(2, plan.getAuditTrail().size());
        assertThrows(IllegalArgumentException.class, () -> plan.updateContent("x", Map.of(), " "));
        assertThrows(IllegalArgumentException.class, () -> plan.updateContent(" ", Map.of(), EXECUTOR));
    }

    @Test
    @DisplayName("an ACTIVE or RETIRED plan cannot be edited")
    void updateContentOutsideDraft() {
        Plan plan = draft();
        plan.activate(EXECUTOR);
        assertThrows(InvalidPlanStatusException.class, () -> plan.updateContent("x", Map.of(), EXECUTOR));
        plan.retire(EXECUTOR);
        assertThrows(InvalidPlanStatusException.class, () -> plan.updateContent("x", Map.of(), EXECUTOR));
    }

    @Test
    @DisplayName("the lifecycle is DRAFT -> ACTIVE -> RETIRED and nothing else")
    void lifecycle() {
        Plan plan = draft();
        assertThrows(InvalidPlanStatusException.class, () -> plan.retire(EXECUTOR));

        AuditEntry activated = plan.activate(EXECUTOR);
        assertEquals(PlanStatus.ACTIVE, plan.getStatus());
        assertEquals("STATUS_CHANGED", activated.action());
        assertEquals("DRAFT", activated.fromStatus());
        assertEquals("ACTIVE", activated.toStatus());
        assertThrows(InvalidPlanStatusException.class, () -> plan.activate(EXECUTOR));

        AuditEntry retired = plan.retire(EXECUTOR);
        assertEquals(PlanStatus.RETIRED, plan.getStatus());
        assertEquals("RETIRED", retired.toStatus());
        assertThrows(InvalidPlanStatusException.class, () -> plan.activate(EXECUTOR));
        assertThrows(InvalidPlanStatusException.class, () -> plan.retire(EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> draft().activate(null));
        assertEquals(3, plan.getAuditTrail().size());
    }

    @Test
    @DisplayName("entitlement answers allowed with the limit, allowed and unlimited, or not included")
    void entitlement() {
        Plan plan = draft();

        assertEquals(new Entitlement(true, 500L), plan.entitlement("assets"));
        assertEquals(new Entitlement(true, null), plan.entitlement("sites"));
        assertEquals(new Entitlement(false, null), plan.entitlement("sso"));
        assertTrue(plan.entitlement("discovery").allowed());
        assertFalse(Entitlement.of(Map.of("sso", 0L), "sso").allowed());
    }

    @Test
    @DisplayName("reconstitute rebuilds the aggregate, tolerating a missing audit trail, and refuses missing fields")
    void reconstitute() {
        Instant now = Instant.now();
        Plan plan = Plan.reconstitute(id, "TEAM", "Team", Map.of("assets", 5L), PlanStatus.ACTIVE, now, now, null);
        assertEquals(PlanStatus.ACTIVE, plan.getStatus());
        assertTrue(plan.getAuditTrail().isEmpty());

        AuditEntry entry = new AuditEntry(now, "INITIATED", EXECUTOR, null, "DRAFT", "d");
        assertEquals(1, Plan.reconstitute(id, "TEAM", "Team", Map.of(), PlanStatus.DRAFT, now, now, List.of(entry)).getAuditTrail().size());

        Map<String, Long> e = Map.of();
        assertThrows(IllegalArgumentException.class, () -> Plan.reconstitute(null, "C", "n", e, PlanStatus.DRAFT, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> Plan.reconstitute(id, null, "n", e, PlanStatus.DRAFT, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> Plan.reconstitute(id, "C", null, e, PlanStatus.DRAFT, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> Plan.reconstitute(id, "C", "n", null, PlanStatus.DRAFT, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> Plan.reconstitute(id, "C", "n", e, null, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> Plan.reconstitute(id, "C", "n", e, PlanStatus.DRAFT, null, now, null));
        assertThrows(IllegalArgumentException.class, () -> Plan.reconstitute(id, "C", "n", e, PlanStatus.DRAFT, now, null, null));
    }

    @Test
    @DisplayName("the exposed entitlements and audit trail are read-only views")
    void readOnlyViews() {
        Plan plan = draft();

        assertThrows(UnsupportedOperationException.class, () -> plan.getEntitlements().put("x", 1L));
        assertThrows(UnsupportedOperationException.class, () -> plan.getAuditTrail().clear());
    }
}
