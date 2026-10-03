package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.PlanResponse;
import com.thinklab.application.mapper.BillingMapper;
import com.thinklab.domain.exception.PlanNotFoundException;
import com.thinklab.domain.model.Plan.PlanStatus;
import com.thinklab.domain.repository.PlanRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Read side of the Plan aggregate (BIAN Behavior Qualifier: {@code retrieve}). */
@Singleton
public class RetrievePlanUseCase {

    private final PlanRepository planRepository;

    public RetrievePlanUseCase(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    public Mono<PlanResponse> byId(UUID id) {
        return planRepository.findById(id)
                .switchIfEmpty(Mono.error(new PlanNotFoundException(id)))
                .map(BillingMapper::toResponse);
    }

    public Flux<PlanResponse> all(PlanStatus status) {
        return planRepository.findAll(status).map(BillingMapper::toResponse);
    }

    public Mono<List<AuditEntryResponse>> auditLog(UUID id) {
        return planRepository.findById(id)
                .switchIfEmpty(Mono.error(new PlanNotFoundException(id)))
                .map(plan -> plan.getAuditTrail().stream().map(BillingMapper::toResponse).toList());
    }
}
