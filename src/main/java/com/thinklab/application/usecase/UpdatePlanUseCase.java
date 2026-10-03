package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdatePlanRequest;
import com.thinklab.domain.exception.PlanNotFoundException;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.repository.PlanRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Replaces a DRAFT plan's name and entitlements (BIAN Behavior Qualifier: {@code update}). Loads the aggregate first so an
 * illegal edit (ACTIVE or RETIRED plan) is refused by the domain model before any write.
 */
@Singleton
public class UpdatePlanUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdatePlanUseCase.class);

    private final PlanRepository planRepository;

    public UpdatePlanUseCase(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    public Mono<Void> execute(UUID id, UpdatePlanRequest request, String executor) {
        log.info("[USE CASE] Updating plan ID: {}", id);

        return planRepository.findById(id)
                .switchIfEmpty(Mono.error(new PlanNotFoundException(id)))
                .flatMap(plan -> {
                    AuditEntry entry = plan.updateContent(request.name(), request.entitlements(), executor);
                    return planRepository.updateContent(id, plan.getName(), plan.getEntitlements(), entry);
                });
    }
}
