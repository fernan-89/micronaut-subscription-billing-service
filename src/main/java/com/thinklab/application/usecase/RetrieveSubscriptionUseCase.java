package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.SubscriptionResponse;
import com.thinklab.application.mapper.BillingMapper;
import com.thinklab.domain.exception.SubscriptionNotFoundException;
import com.thinklab.domain.model.Subscription.SubscriptionStatus;
import com.thinklab.domain.repository.SubscriptionRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Read side of the Subscription aggregate (BIAN Behavior Qualifier: {@code retrieve}), always scoped to one tenant. */
@Singleton
public class RetrieveSubscriptionUseCase {

    private final SubscriptionLookup subscriptionLookup;
    private final SubscriptionRepository subscriptionRepository;

    public RetrieveSubscriptionUseCase(SubscriptionLookup subscriptionLookup, SubscriptionRepository subscriptionRepository) {
        this.subscriptionLookup = subscriptionLookup;
        this.subscriptionRepository = subscriptionRepository;
    }

    public Mono<SubscriptionResponse> byId(UUID id, UUID organisationId) {
        return subscriptionLookup.owned(id, organisationId).map(BillingMapper::toResponse);
    }

    public Flux<SubscriptionResponse> all(UUID organisationId, SubscriptionStatus status) {
        return subscriptionRepository.findAllByOrganisationId(organisationId, status).map(BillingMapper::toResponse);
    }

    public Mono<SubscriptionResponse> current(UUID organisationId) {
        return subscriptionRepository.findCurrentByOrganisationId(organisationId)
                .switchIfEmpty(Mono.error(new SubscriptionNotFoundException(
                        "Organisation " + organisationId + " has no current subscription.")))
                .map(BillingMapper::toResponse);
    }

    public Mono<List<AuditEntryResponse>> auditLog(UUID id, UUID organisationId) {
        return subscriptionLookup.owned(id, organisationId)
                .map(subscription -> subscription.getAuditTrail().stream().map(BillingMapper::toResponse).toList());
    }
}
