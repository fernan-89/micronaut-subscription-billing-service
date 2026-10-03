package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.PlanResponse;
import com.thinklab.application.dto.response.SubscriptionResponse;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Plan;
import com.thinklab.domain.model.Subscription;

/** Static factory mapper for Plan/Subscription DTOs and Domain Entities. Enforces the DTO Isolation Pattern. */
public final class BillingMapper {

    private BillingMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static PlanResponse toResponse(Plan plan) {
        return new PlanResponse(plan.getId(), plan.getCode(), plan.getName(), plan.getEntitlements(),
                plan.getStatus().name(), plan.getCreatedAt(), plan.getUpdatedAt());
    }

    public static SubscriptionResponse toResponse(Subscription subscription) {
        return new SubscriptionResponse(subscription.getId(), subscription.getOrganisationId(), subscription.getPlanCode(),
                subscription.getStatus().name(), subscription.getCreatedAt(), subscription.getUpdatedAt());
    }

    public static AuditEntryResponse toResponse(AuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus(), entry.toStatus(), entry.detail());
    }
}
