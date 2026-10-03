package com.thinklab.application.usecase;

import com.thinklab.domain.exception.PlanNotFoundException;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Plan;
import com.thinklab.domain.model.Plan.PlanStatus;
import com.thinklab.domain.repository.PlanRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Governs the Plan lifecycle (BIAN Behavior Qualifier: {@code control}). Loads the aggregate first, lets the domain model
 * refuse an illegal move (HTTP 409) and only then issues the granular update together with the audit entry.
 */
@Singleton
public class ControlPlanUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlPlanUseCase.class);

    private final PlanRepository planRepository;

    public ControlPlanUseCase(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    public Mono<Void> execute(UUID id, Action action, String executor) {
        log.info("[USE CASE] Controlling plan lifecycle: {} for ID: {}", action, id);

        return planRepository.findById(id)
                .switchIfEmpty(Mono.error(new PlanNotFoundException(id)))
                .flatMap(plan -> {
                    AuditEntry entry = action.transition().apply(plan, executor);
                    return planRepository.updateStatus(id, PlanStatus.valueOf(entry.toStatus()), entry);
                });
    }

    public enum Action {
        ACTIVATE(Plan::activate),
        RETIRE(Plan::retire);

        private final BiFunction<Plan, String, AuditEntry> transition;

        Action(BiFunction<Plan, String, AuditEntry> transition) {
            this.transition = transition;
        }

        BiFunction<Plan, String, AuditEntry> transition() {
            return transition;
        }
    }
}
