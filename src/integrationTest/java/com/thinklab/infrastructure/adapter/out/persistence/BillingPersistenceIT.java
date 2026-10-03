package com.thinklab.infrastructure.adapter.out.persistence;

import com.thinklab.application.dto.request.InitiatePlanRequest;
import com.thinklab.application.dto.request.SubscriptionPlanRequest;
import com.thinklab.application.dto.response.EntitlementResponse;
import com.thinklab.application.dto.response.SubscriptionResponse;
import com.thinklab.application.usecase.ControlPlanUseCase;
import com.thinklab.application.usecase.ControlSubscriptionUseCase;
import com.thinklab.application.usecase.EvaluateEntitlementUseCase;
import com.thinklab.application.usecase.InitiatePlanUseCase;
import com.thinklab.application.usecase.InitiateSubscriptionUseCase;
import com.thinklab.application.usecase.RetrievePlanUseCase;
import com.thinklab.application.usecase.RetrieveSubscriptionUseCase;
import com.thinklab.domain.exception.DuplicatePlanException;
import com.thinklab.domain.exception.DuplicateSubscriptionException;
import com.thinklab.domain.model.Subscription;
import com.thinklab.domain.model.Subscription.SubscriptionStatus;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.SubscriptionRepository;
import com.thinklab.infrastructure.adapter.out.integration.hashservice.HashServiceAdapter;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The billing domain against a real MongoDB, proving what no mock can: the unique plan-code index and the partial unique
 * current-subscription index really hold (also under concurrent writers), a cancelled subscription really frees the slot, and
 * plans (a {@code Map<String, Long>} of entitlements) and their audit trails survive the BSON round trip (ADR-031).
 *
 * <p>{@code packages = "com.thinklab"}: otherwise Micronaut Data MongoDB stops mapping {@code @Id} to {@code _id} for entities
 * outside the test's own package.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillingPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "it_subscription_billing_it";
    private static final String EXECUTOR = "billing-admin";

    /** The hash service is another process; billing only needs a fresh UUID from it. */
    @Singleton
    @Replaces(HashServiceAdapter.class)
    static class FixedHashService implements HashServicePort {
        @Override
        public Mono<UUID> generateSovereignId(String purpose) {
            return Mono.fromSupplier(UUID::randomUUID);
        }

        @Override
        public Mono<String> hashSensitiveData(String rawData) {
            return Mono.just(rawData);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE), "thinklab.billing.default-plan-code", "IT_DEFAULT");
    }

    @Inject InitiatePlanUseCase initiatePlan;
    @Inject ControlPlanUseCase controlPlan;
    @Inject RetrievePlanUseCase retrievePlan;
    @Inject InitiateSubscriptionUseCase initiateSubscription;
    @Inject ControlSubscriptionUseCase controlSubscription;
    @Inject RetrieveSubscriptionUseCase retrieveSubscription;
    @Inject EvaluateEntitlementUseCase evaluate;
    @Inject SubscriptionRepository subscriptions;

    private String code() {
        return "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private UUID activePlan(String code, Map<String, Long> entitlements) {
        UUID id = initiatePlan.execute(new InitiatePlanRequest(code, "Plan " + code, entitlements), EXECUTOR).block().id();
        controlPlan.execute(id, ControlPlanUseCase.Action.ACTIVATE, EXECUTOR).block();
        return id;
    }

    @Test
    @DisplayName("a plan with its entitlements and ledger round-trips through MongoDB, and a duplicate code is refused by the unique index")
    void planRoundTripAndUniqueCode() {
        String code = code();
        UUID id = initiatePlan.execute(new InitiatePlanRequest(code, "Team", Map.of("assets", 500L, "sites", -1L)), EXECUTOR).block().id();
        controlPlan.execute(id, ControlPlanUseCase.Action.ACTIVATE, EXECUTOR).block();

        var stored = retrievePlan.byId(id).block();
        assertEquals("ACTIVE", stored.status());
        assertEquals(Map.of("assets", 500L, "sites", -1L), stored.entitlements());
        var log = retrievePlan.auditLog(id).block();
        assertEquals(2, log.size());
        assertEquals("STATUS_CHANGED", log.get(1).action());

        assertThrows(DuplicatePlanException.class,
                () -> initiatePlan.execute(new InitiatePlanRequest(code, "Again", Map.of()), EXECUTOR).block());
    }

    @Test
    @DisplayName("an organisation gets one current subscription: concurrent initiations leave exactly one, and cancelling frees the slot")
    void oneCurrentSubscription() {
        String code = code();
        activePlan(code, Map.of("assets", 5L));
        UUID org = UUID.randomUUID();

        List<Object> outcomes = Flux.range(0, 8)
                .flatMap(i -> initiateSubscription.execute(org, new SubscriptionPlanRequest(code), EXECUTOR).cast(Object.class)
                        .onErrorResume(e -> Mono.just(e)), 8)
                .collectList().block();

        long created = outcomes.stream().filter(o -> o instanceof SubscriptionResponse).count();
        long refused = outcomes.stream().filter(o -> o instanceof DuplicateSubscriptionException).count();
        assertEquals(1, created, outcomes.toString());
        assertEquals(7, refused, outcomes.toString());

        SubscriptionResponse current = retrieveSubscription.current(org).block();
        assertEquals("TRIALING", current.status());

        controlSubscription.execute(current.id(), org, ControlSubscriptionUseCase.Action.CANCEL, EXECUTOR).block();
        SubscriptionResponse next = initiateSubscription.execute(org, new SubscriptionPlanRequest(code), EXECUTOR).block();
        assertFalse(next.id().equals(current.id()));
        assertEquals(2, retrieveSubscription.all(org, null).collectList().block().size());
        assertEquals(1, retrieveSubscription.all(org, SubscriptionStatus.CANCELLED).collectList().block().size());
    }

    @Test
    @DisplayName("entitlements follow the subscription's plan, the default plan without one, and nothing once suspended")
    void entitlements() {
        String code = code();
        activePlan(code, Map.of("assets", 5L, "discovery", 1L));
        activePlan("IT_DEFAULT", Map.of("assets", 2L));
        UUID org = UUID.randomUUID();

        EntitlementResponse unsubscribed = evaluate.execute(org, "assets").block();
        assertEquals("DEFAULT_PLAN", unsubscribed.source());
        assertEquals(2L, unsubscribed.limit());

        SubscriptionResponse subscription = initiateSubscription.execute(org, new SubscriptionPlanRequest(code), EXECUTOR).block();
        EntitlementResponse subscribed = evaluate.execute(org, "assets").block();
        assertEquals("SUBSCRIPTION", subscribed.source());
        assertEquals(5L, subscribed.limit());
        assertFalse(evaluate.execute(org, "sso").block().allowed());

        controlSubscription.execute(subscription.id(), org, ControlSubscriptionUseCase.Action.ACTIVATE, EXECUTOR).block();
        controlSubscription.execute(subscription.id(), org, ControlSubscriptionUseCase.Action.SUSPEND, EXECUTOR).block();
        EntitlementResponse suspended = evaluate.execute(org, "assets").block();
        assertFalse(suspended.allowed());
        assertEquals("SUSPENDED", suspended.source());
    }

    @Test
    @DisplayName("a subscription's ledger survives the round trip and another tenant cannot read it")
    void subscriptionLedgerAndTenancy() {
        String code = code();
        activePlan(code, Map.of());
        UUID org = UUID.randomUUID();
        SubscriptionResponse subscription = initiateSubscription.execute(org, new SubscriptionPlanRequest(code), EXECUTOR).block();
        controlSubscription.execute(subscription.id(), org, ControlSubscriptionUseCase.Action.ACTIVATE, EXECUTOR).block();

        var log = retrieveSubscription.auditLog(subscription.id(), org).block();
        assertEquals(2, log.size());
        assertEquals("TRIALING", log.get(1).fromStatus());
        assertEquals("ACTIVE", log.get(1).toStatus());

        Subscription stored = subscriptions.findById(subscription.id()).block();
        assertTrue(stored.isCurrent());
        assertThrows(com.thinklab.domain.exception.SubscriptionNotFoundException.class,
                () -> retrieveSubscription.byId(subscription.id(), UUID.randomUUID()).block());
    }
}
