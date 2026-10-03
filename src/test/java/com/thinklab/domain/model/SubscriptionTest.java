package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidSubscriptionStatusException;
import com.thinklab.domain.model.Subscription.SubscriptionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionTest {

    private static final String EXECUTOR = "billing-admin";
    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();

    private Subscription in(SubscriptionStatus status) {
        Instant now = Instant.now();
        return Subscription.reconstitute(id, org, "TEAM", status, now, now, null);
    }

    @Test
    @DisplayName("createNew starts a TRIALING subscription on the plan, with an INITIATED audit entry")
    void createNew() {
        Subscription subscription = Subscription.createNew(id, org, "TEAM", EXECUTOR);

        assertEquals(SubscriptionStatus.TRIALING, subscription.getStatus());
        assertEquals("TEAM", subscription.getPlanCode());
        assertEquals(org, subscription.getOrganisationId());
        assertTrue(subscription.isCurrent());
        assertEquals(1, subscription.getAuditTrail().size());
        AuditEntry first = subscription.getAuditTrail().get(0);
        assertEquals("INITIATED", first.action());
        assertNull(first.fromStatus());
        assertEquals("TRIALING", first.toStatus());
    }

    @Test
    @DisplayName("createNew refuses missing ids, a blank plan code and a missing executor")
    void createNewGuards() {
        assertThrows(IllegalArgumentException.class, () -> Subscription.createNew(null, org, "TEAM", EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Subscription.createNew(id, null, "TEAM", EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Subscription.createNew(id, org, null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Subscription.createNew(id, org, " ", EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> Subscription.createNew(id, org, "TEAM", null));
        assertThrows(IllegalArgumentException.class, () -> Subscription.createNew(id, org, "TEAM", " "));
    }

    private void expectMoves(BiFunction<Subscription, String, AuditEntry> move, Set<SubscriptionStatus> legalOrigins, SubscriptionStatus target) {
        for (SubscriptionStatus origin : SubscriptionStatus.values()) {
            Subscription subscription = in(origin);
            if (legalOrigins.contains(origin)) {
                AuditEntry entry = move.apply(subscription, EXECUTOR);
                assertEquals(target, subscription.getStatus(), origin + " -> " + target);
                assertEquals("STATUS_CHANGED", entry.action());
                assertEquals(origin.name(), entry.fromStatus());
                assertEquals(target.name(), entry.toStatus());
                assertEquals(1, subscription.getAuditTrail().size());
            } else {
                assertThrows(InvalidSubscriptionStatusException.class, () -> move.apply(subscription, EXECUTOR), origin + " -> " + target);
                assertEquals(origin, subscription.getStatus());
            }
        }
    }

    @Test
    @DisplayName("the transition matrix: activate, mark-past-due, suspend and cancel move only from their legal origins")
    void transitionMatrix() {
        expectMoves(Subscription::activate, EnumSet.of(SubscriptionStatus.TRIALING, SubscriptionStatus.PAST_DUE, SubscriptionStatus.SUSPENDED), SubscriptionStatus.ACTIVE);
        expectMoves(Subscription::markPastDue, EnumSet.of(SubscriptionStatus.ACTIVE), SubscriptionStatus.PAST_DUE);
        expectMoves(Subscription::suspend, EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE), SubscriptionStatus.SUSPENDED);
        expectMoves(Subscription::cancel, EnumSet.of(SubscriptionStatus.TRIALING, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, SubscriptionStatus.SUSPENDED),
                SubscriptionStatus.CANCELLED);
    }

    @Test
    @DisplayName("every transition needs an executor")
    void transitionsNeedAnExecutor() {
        assertThrows(IllegalArgumentException.class, () -> in(SubscriptionStatus.TRIALING).activate(null));
        assertThrows(IllegalArgumentException.class, () -> in(SubscriptionStatus.TRIALING).cancel(" "));
    }

    @Test
    @DisplayName("changePlan moves the subscription and records PLAN_CHANGED, in any non-cancelled status")
    void changePlan() {
        for (SubscriptionStatus status : Subscription.CURRENT) {
            Subscription subscription = in(status);

            AuditEntry entry = subscription.changePlan("ENTERPRISE", EXECUTOR);

            assertEquals("ENTERPRISE", subscription.getPlanCode());
            assertEquals(status, subscription.getStatus());
            assertEquals("PLAN_CHANGED", entry.action());
            assertEquals(status.name(), entry.fromStatus());
            assertTrue(entry.detail().contains("TEAM") && entry.detail().contains("ENTERPRISE"));
        }
    }

    @Test
    @DisplayName("changePlan is refused once cancelled, onto the same plan, and without a plan or an executor")
    void changePlanRefusals() {
        assertThrows(InvalidSubscriptionStatusException.class, () -> in(SubscriptionStatus.CANCELLED).changePlan("ENTERPRISE", EXECUTOR));
        assertThrows(InvalidSubscriptionStatusException.class, () -> in(SubscriptionStatus.ACTIVE).changePlan("TEAM", EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> in(SubscriptionStatus.ACTIVE).changePlan(null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> in(SubscriptionStatus.ACTIVE).changePlan(" ", EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> in(SubscriptionStatus.ACTIVE).changePlan("ENTERPRISE", null));
    }

    @Test
    @DisplayName("only a CANCELLED subscription is not current")
    void current() {
        assertFalse(in(SubscriptionStatus.CANCELLED).isCurrent());
        assertEquals(4, Subscription.CURRENT.size());
    }

    @Test
    @DisplayName("reconstitute tolerates a missing audit trail and refuses missing fields")
    void reconstitute() {
        Instant now = Instant.now();
        assertTrue(Subscription.reconstitute(id, org, "TEAM", SubscriptionStatus.ACTIVE, now, now, null).getAuditTrail().isEmpty());
        AuditEntry entry = new AuditEntry(now, "INITIATED", EXECUTOR, null, "TRIALING", "d");
        Subscription withTrail = Subscription.reconstitute(id, org, "TEAM", SubscriptionStatus.ACTIVE, now, now, List.of(entry));
        assertEquals(1, withTrail.getAuditTrail().size());
        assertThrows(UnsupportedOperationException.class, () -> withTrail.getAuditTrail().clear());
        assertEquals(now, withTrail.getCreatedAt());

        assertThrows(IllegalArgumentException.class, () -> Subscription.reconstitute(null, org, "T", SubscriptionStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> Subscription.reconstitute(id, null, "T", SubscriptionStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> Subscription.reconstitute(id, org, null, SubscriptionStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> Subscription.reconstitute(id, org, "T", null, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> Subscription.reconstitute(id, org, "T", SubscriptionStatus.ACTIVE, null, now, null));
        assertThrows(IllegalArgumentException.class, () -> Subscription.reconstitute(id, org, "T", SubscriptionStatus.ACTIVE, now, null, null));
    }
}
