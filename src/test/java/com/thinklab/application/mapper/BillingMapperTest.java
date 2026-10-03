package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.PlanResponse;
import com.thinklab.application.dto.response.SubscriptionResponse;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Plan;
import com.thinklab.domain.model.Subscription;
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

class BillingMapperTest {

    @Test
    @DisplayName("a Plan, a Subscription and an audit entry map field for field")
    void mapsEverything() {
        UUID id = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        Plan plan = Plan.createNew(id, "TEAM", "Team", Map.of("assets", 5L), "admin");
        Subscription subscription = Subscription.createNew(id, org, "TEAM", "admin");
        AuditEntry entry = new AuditEntry(Instant.parse("2026-10-03T12:00:00Z"), "UPDATED", "admin", "DRAFT", "DRAFT", "d");

        PlanResponse planResponse = BillingMapper.toResponse(plan);
        assertEquals(id, planResponse.id());
        assertEquals("TEAM", planResponse.code());
        assertEquals("Team", planResponse.name());
        assertEquals(Map.of("assets", 5L), planResponse.entitlements());
        assertEquals("DRAFT", planResponse.status());
        assertEquals(plan.getCreatedAt(), planResponse.createdAt());

        SubscriptionResponse subscriptionResponse = BillingMapper.toResponse(subscription);
        assertEquals(org, subscriptionResponse.organisationId());
        assertEquals("TEAM", subscriptionResponse.planCode());
        assertEquals("TRIALING", subscriptionResponse.status());

        AuditEntryResponse auditResponse = BillingMapper.toResponse(entry);
        assertEquals("UPDATED", auditResponse.action());
        assertEquals("DRAFT", auditResponse.fromStatus());
        assertEquals("d", auditResponse.detail());
    }

    @Test
    @DisplayName("the mapper is a non-instantiable utility class")
    void utilityClass() throws Exception {
        Constructor<BillingMapper> constructor = BillingMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    }
}
