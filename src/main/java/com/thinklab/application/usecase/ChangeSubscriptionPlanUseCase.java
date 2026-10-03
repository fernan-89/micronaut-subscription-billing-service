package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.SubscriptionPlanRequest;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.repository.SubscriptionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Moves a subscription to another plan (BIAN Behavior Qualifier: {@code plan/update}). The new plan must be on sale; the
 * aggregate refuses the change once the subscription is cancelled or when it is already on that plan.
 */
@Singleton
public class ChangeSubscriptionPlanUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChangeSubscriptionPlanUseCase.class);

    private final SubscriptionLookup subscriptionLookup;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanAvailability planAvailability;

    public ChangeSubscriptionPlanUseCase(SubscriptionLookup subscriptionLookup, SubscriptionRepository subscriptionRepository,
                                         PlanAvailability planAvailability) {
        this.subscriptionLookup = subscriptionLookup;
        this.subscriptionRepository = subscriptionRepository;
        this.planAvailability = planAvailability;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, SubscriptionPlanRequest request, String executor) {
        log.info("[USE CASE] Changing plan of subscription ID: {} to {}", id, request.planCode());

        return subscriptionLookup.owned(id, organisationId)
                .flatMap(subscription -> planAvailability.requireOnSale(request.planCode())
                        .then(Mono.defer(() -> {
                            AuditEntry entry = subscription.changePlan(request.planCode(), executor);
                            return subscriptionRepository.updatePlan(id, request.planCode(), entry);
                        })));
    }
}
