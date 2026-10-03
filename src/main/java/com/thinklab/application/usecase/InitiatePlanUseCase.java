package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiatePlanRequest;
import com.thinklab.application.dto.response.PlanResponse;
import com.thinklab.application.mapper.BillingMapper;
import com.thinklab.domain.model.Plan;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.PlanRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** Orchestrates Plan creation (BIAN Behavior Qualifier: {@code initiate}): Sovereign ID, then the aggregate, then the insert. */
@Singleton
public class InitiatePlanUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiatePlanUseCase.class);

    private final HashServicePort hashServicePort;
    private final PlanRepository planRepository;

    public InitiatePlanUseCase(HashServicePort hashServicePort, PlanRepository planRepository) {
        this.hashServicePort = hashServicePort;
        this.planRepository = planRepository;
    }

    public Mono<PlanResponse> execute(InitiatePlanRequest request, String executor) {
        log.info("[USE CASE] Initiating plan: {}", request.code());

        return hashServicePort.generateSovereignId("plan-creation")
                .map(id -> Plan.createNew(id, request.code(), request.name(), request.entitlements(), executor))
                .flatMap(planRepository::create)
                .map(BillingMapper::toResponse);
    }
}
