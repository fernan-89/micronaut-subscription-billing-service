package com.thinklab.application.usecase;

import com.thinklab.domain.exception.PlanNotAvailableException;
import com.thinklab.domain.model.Plan.PlanStatus;
import com.thinklab.domain.repository.PlanRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

/** Answers whether a plan can be chosen for a subscription: it must exist and be ACTIVE (on sale). */
@Singleton
public class PlanAvailability {

    private final PlanRepository planRepository;

    public PlanAvailability(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    public Mono<Void> requireOnSale(String planCode) {
        return planRepository.findByCode(planCode)
                .switchIfEmpty(Mono.error(new PlanNotAvailableException("Plan " + planCode + " does not exist.")))
                .flatMap(plan -> plan.getStatus() == PlanStatus.ACTIVE
                        ? Mono.<Void>empty()
                        : Mono.error(new PlanNotAvailableException("Plan " + planCode + " is " + plan.getStatus() + ", not on sale.")));
    }
}
