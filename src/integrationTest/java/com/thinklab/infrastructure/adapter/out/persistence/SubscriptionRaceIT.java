package com.thinklab.infrastructure.adapter.out.persistence;

import com.thinklab.application.dto.request.InitiatePlanRequest;
import com.thinklab.application.dto.request.SubscriptionPlanRequest;
import com.thinklab.application.usecase.ChangeSubscriptionPlanUseCase;
import com.thinklab.application.usecase.ControlPlanUseCase;
import com.thinklab.application.usecase.ControlSubscriptionUseCase;
import com.thinklab.application.usecase.InitiatePlanUseCase;
import com.thinklab.application.usecase.InitiateSubscriptionUseCase;
import com.thinklab.application.usecase.RetrieveSubscriptionUseCase;
import com.thinklab.domain.exception.InvalidSubscriptionStatusException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The subscription transition races against a real MongoDB (platform race audit, ACTION-PLAN 0.5): two simultaneous changes to one subscription must
 * have exactly one winner, and the stored plan or status must agree with the trail. (The hash service is the test double of {@link BillingPersistenceIT}.)
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionRaceIT implements TestPropertyProvider {

    private static final String DATABASE = "it_subscription_billing_race_it";
    private static final String EXECUTOR = "billing-admin";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE), "thinklab.billing.default-plan-code", "IT_DEFAULT");
    }

    @Inject InitiatePlanUseCase initiatePlan;
    @Inject ControlPlanUseCase controlPlan;
    @Inject InitiateSubscriptionUseCase initiateSubscription;
    @Inject ControlSubscriptionUseCase control;
    @Inject ChangeSubscriptionPlanUseCase changePlan;
    @Inject RetrieveSubscriptionUseCase retrieve;

    private String activePlan() {
        String code = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        UUID id = initiatePlan.execute(new InitiatePlanRequest(code, "Plan " + code, Map.of("assets", 10L)), EXECUTOR).block().id();
        controlPlan.execute(id, ControlPlanUseCase.Action.ACTIVATE, EXECUTOR).block();
        return code;
    }

    private static List<String> collect(Flux<String> results) {
        return results.collectList().block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("ten activations of one subscription at once: exactly one wins and the others are refused with a conflict")
    void tenActivationsAtOnce() {
        UUID organisation = UUID.randomUUID();
        var subscription = initiateSubscription.execute(organisation, new SubscriptionPlanRequest(activePlan()), EXECUTOR).block();

        List<String> results = collect(Flux.range(0, 10)
                .flatMap(i -> control.execute(subscription.id(), organisation, ControlSubscriptionUseCase.Action.ACTIVATE, EXECUTOR).thenReturn("OK")
                        .onErrorResume(e -> Mono.just(e.getClass().getSimpleName())), 10));

        assertEquals(1, results.stream().filter("OK"::equals).count(), results.toString());
        assertEquals(9, results.stream().filter(InvalidSubscriptionStatusException.class.getSimpleName()::equals).count(), results.toString());
        assertEquals(2, retrieve.auditLog(subscription.id(), organisation).block().size(), "one entry for starting and one for the single activation that won");
    }

    @Test
    @DisplayName("ten plan changes of one subscription at once: whoever wins, the trail is one unbroken chain from the first plan to the stored one")
    void tenPlanChangesAtOnce() {
        UUID organisation = UUID.randomUUID();
        String first = activePlan();
        var subscription = initiateSubscription.execute(organisation, new SubscriptionPlanRequest(first), EXECUTOR).block();
        List<String> targets = Flux.range(0, 10).map(i -> activePlan()).collectList().block();

        List<String> results = collect(Flux.range(0, 10)
                .flatMap(i -> changePlan.execute(subscription.id(), organisation, new SubscriptionPlanRequest(targets.get(i)), EXECUTOR)
                        .thenReturn("OK").onErrorResume(e -> Mono.just(e.getClass().getSimpleName())), 10));

        long winners = results.stream().filter("OK"::equals).count();
        assertTrue(winners >= 1 && winners < 10, "ten changes made from the same plan cannot all win: " + results);
        var trail = retrieve.auditLog(subscription.id(), organisation).block();
        assertEquals(1 + winners, trail.size());
        Pattern change = Pattern.compile("from (\\S+) to (\\S+)\\.");
        String expectedFrom = first;
        for (var entry : trail.subList(1, trail.size())) {
            Matcher m = change.matcher(entry.detail());
            assertTrue(m.find(), entry.detail());
            assertEquals(expectedFrom, m.group(1), "each plan change must start from where the previous one ended: " + entry.detail());
            expectedFrom = m.group(2);
        }
        assertEquals(expectedFrom, retrieve.byId(subscription.id(), organisation).block().planCode());
    }

    @Test
    @DisplayName("an activation and a cancellation at once: one wins, and the stored status is the winner's")
    void activateAgainstCancel() {
        UUID organisation = UUID.randomUUID();
        var subscription = initiateSubscription.execute(organisation, new SubscriptionPlanRequest(activePlan()), EXECUTOR).block();

        List<String> results = collect(Flux.just(ControlSubscriptionUseCase.Action.ACTIVATE, ControlSubscriptionUseCase.Action.CANCEL)
                .flatMap(action -> control.execute(subscription.id(), organisation, action, EXECUTOR).thenReturn(action.name()).onErrorResume(e -> Mono.just("REFUSED")), 2));

        List<String> winners = results.stream().filter(r -> !"REFUSED".equals(r)).toList();
        assertEquals(1, winners.size(), results.toString());
        String expected = "ACTIVATE".equals(winners.get(0)) ? "ACTIVE" : "CANCELLED";
        assertEquals(expected, retrieve.byId(subscription.id(), organisation).block().status());
    }
}
