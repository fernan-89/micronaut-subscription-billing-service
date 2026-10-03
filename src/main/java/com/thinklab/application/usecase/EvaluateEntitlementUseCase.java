package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.EntitlementResponse;
import com.thinklab.domain.model.Entitlement;
import com.thinklab.domain.model.Plan;
import com.thinklab.domain.model.Plan.PlanStatus;
import com.thinklab.domain.model.Subscription;
import com.thinklab.domain.model.Subscription.SubscriptionStatus;
import com.thinklab.domain.repository.PlanRepository;
import com.thinklab.domain.repository.SubscriptionRepository;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Answers "may this organisation use this feature?" (BIAN Behavior Qualifier: {@code entitlement/evaluate}, ADR-032).
 *
 * <ol>
 *   <li>The organisation has a current subscription: a {@code SUSPENDED} one allows nothing; any other status (a
 *       {@code PAST_DUE} one is a grace period) is decided by its plan.</li>
 *   <li>No subscription: the default plan stands in ({@code thinklab.billing.default-plan-code}, HOMELAB), so nothing breaks
 *       for a tenant nobody has subscribed yet.</li>
 *   <li>Neither a usable subscription plan nor an ACTIVE default plan: {@code UNMANAGED}, allowed (fail-open, like ADR-027 of the
 *       asset registry), so a catalogue that has not been set up never locks anyone out.</li>
 * </ol>
 */
@Singleton
public class EvaluateEntitlementUseCase {

    private static final Logger log = LoggerFactory.getLogger(EvaluateEntitlementUseCase.class);

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final String defaultPlanCode;

    public EvaluateEntitlementUseCase(SubscriptionRepository subscriptionRepository, PlanRepository planRepository,
                                      @Value("${thinklab.billing.default-plan-code:HOMELAB}") String defaultPlanCode) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.defaultPlanCode = defaultPlanCode;
    }

    public Mono<EntitlementResponse> execute(UUID organisationId, String feature) {
        log.info("[USE CASE] Evaluating entitlement [{}] for organisation: {}", feature, organisationId);

        return subscriptionRepository.findCurrentByOrganisationId(organisationId)
                .flatMap(subscription -> decide(subscription, feature))
                .switchIfEmpty(Mono.defer(() -> defaultPlan(feature)));
    }

    private Mono<EntitlementResponse> decide(Subscription subscription, String feature) {
        if (subscription.getStatus() == SubscriptionStatus.SUSPENDED) {
            return Mono.just(new EntitlementResponse(feature, false, null, "SUSPENDED", subscription.getPlanCode()));
        }
        return planRepository.findByCode(subscription.getPlanCode())
                .map(plan -> answer(plan, feature, "SUBSCRIPTION"))
                .switchIfEmpty(Mono.just(unmanaged(feature)));
    }

    private Mono<EntitlementResponse> defaultPlan(String feature) {
        return planRepository.findByCode(defaultPlanCode)
                .filter(plan -> plan.getStatus() == PlanStatus.ACTIVE)
                .map(plan -> answer(plan, feature, "DEFAULT_PLAN"))
                .switchIfEmpty(Mono.just(unmanaged(feature)));
    }

    private static EntitlementResponse answer(Plan plan, String feature, String source) {
        Entitlement entitlement = plan.entitlement(feature);
        return new EntitlementResponse(feature, entitlement.allowed(), entitlement.limit(), source, plan.getCode());
    }

    private static EntitlementResponse unmanaged(String feature) {
        return new EntitlementResponse(feature, true, null, "UNMANAGED", null);
    }
}
