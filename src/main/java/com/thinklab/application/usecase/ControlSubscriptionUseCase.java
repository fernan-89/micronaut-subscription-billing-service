package com.thinklab.application.usecase;

import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Subscription;
import com.thinklab.domain.model.Subscription.SubscriptionStatus;
import com.thinklab.domain.repository.SubscriptionRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Governs the Subscription lifecycle (BIAN Behavior Qualifier: {@code control}). Loads the aggregate first (scoped to the
 * tenant), lets the domain model refuse an illegal move (HTTP 409) and only then issues the granular update together with the
 * audit entry.
 */
@Singleton
public class ControlSubscriptionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlSubscriptionUseCase.class);

    private final SubscriptionLookup subscriptionLookup;
    private final SubscriptionRepository subscriptionRepository;

    public ControlSubscriptionUseCase(SubscriptionLookup subscriptionLookup, SubscriptionRepository subscriptionRepository) {
        this.subscriptionLookup = subscriptionLookup;
        this.subscriptionRepository = subscriptionRepository;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, Action action, String executor) {
        log.info("[USE CASE] Controlling subscription lifecycle: {} for ID: {}", action, id);

        return subscriptionLookup.owned(id, organisationId)
                .flatMap(subscription -> {
                    AuditEntry entry = action.transition().apply(subscription, executor);
                    return subscriptionRepository.updateStatus(id, SubscriptionStatus.valueOf(entry.toStatus()), entry);
                });
    }

    public enum Action {
        ACTIVATE(Subscription::activate),
        MARK_PAST_DUE(Subscription::markPastDue),
        SUSPEND(Subscription::suspend),
        CANCEL(Subscription::cancel);

        private final BiFunction<Subscription, String, AuditEntry> transition;

        Action(BiFunction<Subscription, String, AuditEntry> transition) {
            this.transition = transition;
        }

        BiFunction<Subscription, String, AuditEntry> transition() {
            return transition;
        }
    }
}
