package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.SubscriptionPlanRequest;
import com.thinklab.application.dto.response.SubscriptionResponse;
import com.thinklab.application.mapper.BillingMapper;
import com.thinklab.domain.exception.DuplicateSubscriptionException;
import com.thinklab.domain.model.Subscription;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.SubscriptionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Starts an organisation's subscription (BIAN Behavior Qualifier: {@code initiate}). The plan must be on sale (ACTIVE), and an
 * organisation that already has a non-cancelled subscription is refused up front; the repository's partial unique index is the
 * atomic backstop for two concurrent initiations that both pass this check.
 */
@Singleton
public class InitiateSubscriptionUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateSubscriptionUseCase.class);

    private final HashServicePort hashServicePort;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanAvailability planAvailability;

    public InitiateSubscriptionUseCase(HashServicePort hashServicePort, SubscriptionRepository subscriptionRepository,
                                       PlanAvailability planAvailability) {
        this.hashServicePort = hashServicePort;
        this.subscriptionRepository = subscriptionRepository;
        this.planAvailability = planAvailability;
    }

    public Mono<SubscriptionResponse> execute(UUID organisationId, SubscriptionPlanRequest request, String executor) {
        log.info("[USE CASE] Initiating subscription for organisation: {} plan: {}", organisationId, request.planCode());

        return planAvailability.requireOnSale(request.planCode())
                .then(Mono.defer(() -> subscriptionRepository.findCurrentByOrganisationId(organisationId)
                        .flatMap(existing -> Mono.<Subscription>error(new DuplicateSubscriptionException(
                                "Organisation " + organisationId + " already has a subscription (" + existing.getStatus() + ").")))
                        .switchIfEmpty(Mono.defer(() -> hashServicePort.generateSovereignId("subscription-creation")
                                .map(id -> Subscription.createNew(id, organisationId, request.planCode(), executor))
                                .flatMap(subscriptionRepository::create)))))
                .map(BillingMapper::toResponse);
    }
}
