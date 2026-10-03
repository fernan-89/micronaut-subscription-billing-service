package com.thinklab.application.usecase;

import com.thinklab.domain.exception.SubscriptionNotFoundException;
import com.thinklab.domain.model.Subscription;
import com.thinklab.domain.repository.SubscriptionRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Loads a subscription on behalf of one tenant. Another tenant's subscription answers exactly like a missing one (404), so a
 * valid id never reveals that it exists elsewhere.
 */
@Singleton
public class SubscriptionLookup {

    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionLookup(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    public Mono<Subscription> owned(UUID id, UUID organisationId) {
        return subscriptionRepository.findById(id)
                .filter(subscription -> subscription.getOrganisationId().equals(organisationId))
                .switchIfEmpty(Mono.error(new SubscriptionNotFoundException(id)));
    }
}
