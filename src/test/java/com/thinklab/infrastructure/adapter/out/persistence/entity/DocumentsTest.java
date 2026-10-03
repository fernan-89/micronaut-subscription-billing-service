package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Plan;
import com.thinklab.domain.model.Subscription;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PlanDocument.PlanPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.SubscriptionDocument.SubscriptionPersistenceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentsTest {

    @Test
    @DisplayName("a Plan survives the document round trip, ledger included")
    void planRoundTrip() {
        Plan plan = Plan.createNew(UUID.randomUUID(), "TEAM", "Team", Map.of("assets", 5L, "sites", -1L), "admin");
        plan.activate("admin");

        PlanDocument document = PlanPersistenceMapper.toDocument(plan);
        assertEquals("ACTIVE", document.getStatus());
        assertEquals(2, document.getAuditTrail().size());
        Plan back = PlanPersistenceMapper.toDomain(document);

        assertEquals(plan.getId(), back.getId());
        assertEquals("TEAM", back.getCode());
        assertEquals(plan.getEntitlements(), back.getEntitlements());
        assertEquals(plan.getStatus(), back.getStatus());
        assertEquals(plan.getCreatedAt(), back.getCreatedAt());
        assertEquals(plan.getAuditTrail(), back.getAuditTrail());
    }

    @Test
    @DisplayName("a Subscription survives the document round trip, ledger included")
    void subscriptionRoundTrip() {
        Subscription subscription = Subscription.createNew(UUID.randomUUID(), UUID.randomUUID(), "TEAM", "admin");
        subscription.activate("admin");

        SubscriptionDocument document = SubscriptionPersistenceMapper.toDocument(subscription);
        assertEquals("ACTIVE", document.getStatus());
        Subscription back = SubscriptionPersistenceMapper.toDomain(document);

        assertEquals(subscription.getId(), back.getId());
        assertEquals(subscription.getOrganisationId(), back.getOrganisationId());
        assertEquals("TEAM", back.getPlanCode());
        assertEquals(subscription.getStatus(), back.getStatus());
        assertEquals(subscription.getUpdatedAt(), back.getUpdatedAt());
        assertEquals(subscription.getAuditTrail(), back.getAuditTrail());
    }

    @Test
    @DisplayName("an audit entry survives its document form, including the initiating entry without a from-status")
    void auditEntryRoundTrip() {
        AuditEntry first = new AuditEntry(Instant.parse("2026-10-03T12:00:00Z"), "INITIATED", "admin", null, "DRAFT", "d");

        assertEquals(first, AuditEntryDocument.fromDomain(first).toDomain());
    }

    @Test
    @DisplayName("documents expose every field through plain accessors, as the BSON codec needs")
    void accessors() {
        PlanDocument plan = new PlanDocument();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        plan.setId(id);
        plan.setCode("C");
        plan.setName("n");
        plan.setEntitlements(Map.of("a", 1L));
        plan.setStatus("DRAFT");
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        plan.setAuditTrail(java.util.List.of());
        assertEquals(id, plan.getId());
        assertEquals("C", plan.getCode());
        assertEquals("n", plan.getName());
        assertEquals(Map.of("a", 1L), plan.getEntitlements());
        assertEquals(now, plan.getUpdatedAt());

        SubscriptionDocument subscription = new SubscriptionDocument();
        subscription.setId(id);
        subscription.setOrganisationId(id);
        subscription.setPlanCode("C");
        subscription.setStatus("ACTIVE");
        subscription.setCreatedAt(now);
        subscription.setUpdatedAt(now);
        subscription.setAuditTrail(java.util.List.of());
        assertEquals(id, subscription.getOrganisationId());
        assertEquals("C", subscription.getPlanCode());
        assertEquals(now, subscription.getCreatedAt());
        assertEquals(0, subscription.getAuditTrail().size());
    }

    @Test
    @DisplayName("the persistence mappers are non-instantiable utility classes")
    void utilityClasses() throws Exception {
        for (Class<?> type : new Class<?>[]{PlanPersistenceMapper.class, SubscriptionPersistenceMapper.class}) {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);
            assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
        }
    }
}
