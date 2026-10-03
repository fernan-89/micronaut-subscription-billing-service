package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiatePlanRequest;
import com.thinklab.application.dto.request.UpdatePlanRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.domain.exception.DuplicatePlanException;
import com.thinklab.domain.exception.InvalidPlanStatusException;
import com.thinklab.domain.exception.PlanNotFoundException;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Plan;
import com.thinklab.domain.model.Plan.PlanStatus;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanUseCasesTest {

    private static final String EXECUTOR = "billing-admin";

    @Mock private PlanRepository planRepository;
    @Mock private HashServicePort hashServicePort;

    private final UUID id = UUID.randomUUID();
    private Plan plan;

    @BeforeEach
    void setUp() {
        plan = Plan.createNew(id, "TEAM", "Team", Map.of("assets", 500L), EXECUTOR);
    }

    @Test
    @DisplayName("initiate draws a Sovereign ID, drafts the plan and persists it")
    void initiate() {
        when(hashServicePort.generateSovereignId("plan-creation")).thenReturn(Mono.just(id));
        when(planRepository.create(any(Plan.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));

        StepVerifier.create(new InitiatePlanUseCase(hashServicePort, planRepository)
                        .execute(new InitiatePlanRequest("TEAM", "Team", Map.of("assets", 500L)), EXECUTOR))
                .assertNext(response -> {
                    assertEquals(id, response.id());
                    assertEquals("TEAM", response.code());
                    assertEquals("DRAFT", response.status());
                    assertEquals(Map.of("assets", 500L), response.entitlements());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("initiate surfaces a duplicate code and a malformed plan")
    void initiateFailures() {
        when(hashServicePort.generateSovereignId("plan-creation")).thenReturn(Mono.just(id));
        when(planRepository.create(any(Plan.class))).thenReturn(Mono.error(new DuplicatePlanException("taken")));
        InitiatePlanUseCase useCase = new InitiatePlanUseCase(hashServicePort, planRepository);

        StepVerifier.create(useCase.execute(new InitiatePlanRequest("TEAM", "Team", Map.of()), EXECUTOR))
                .expectError(DuplicatePlanException.class).verify();
        StepVerifier.create(useCase.execute(new InitiatePlanRequest("bad code", "Team", Map.of()), EXECUTOR))
                .expectError(IllegalArgumentException.class).verify();
    }

    @Test
    @DisplayName("update rewrites a DRAFT plan through the aggregate and persists name, entitlements and the audit entry")
    void update() {
        when(planRepository.findById(id)).thenReturn(Mono.just(plan));
        when(planRepository.updateContent(eq(id), any(), any(), any(AuditEntry.class))).thenReturn(Mono.empty());

        StepVerifier.create(new UpdatePlanUseCase(planRepository).execute(id, new UpdatePlanRequest("Team 2", Map.of("assets", 900L)), EXECUTOR))
                .verifyComplete();

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(planRepository).updateContent(eq(id), eq("Team 2"), eq(Map.of("assets", 900L)), entry.capture());
        assertEquals("UPDATED", entry.getValue().action());
    }

    @Test
    @DisplayName("update of a missing plan is 404 and of an ACTIVE plan is refused before any write")
    void updateFailures() {
        UpdatePlanUseCase useCase = new UpdatePlanUseCase(planRepository);
        when(planRepository.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(id, new UpdatePlanRequest("n", Map.of()), EXECUTOR)).expectError(PlanNotFoundException.class).verify();

        plan.activate(EXECUTOR);
        when(planRepository.findById(id)).thenReturn(Mono.just(plan));
        StepVerifier.create(useCase.execute(id, new UpdatePlanRequest("n", Map.of()), EXECUTOR)).expectError(InvalidPlanStatusException.class).verify();
        verify(planRepository, never()).updateContent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("control activates then retires, persisting the target status with its audit entry")
    void control() {
        ControlPlanUseCase useCase = new ControlPlanUseCase(planRepository);
        when(planRepository.findById(id)).thenReturn(Mono.just(plan));
        when(planRepository.updateStatus(eq(id), any(PlanStatus.class), any(AuditEntry.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(id, ControlPlanUseCase.Action.ACTIVATE, EXECUTOR)).verifyComplete();
        verify(planRepository).updateStatus(eq(id), eq(PlanStatus.ACTIVE), any(AuditEntry.class));

        StepVerifier.create(useCase.execute(id, ControlPlanUseCase.Action.RETIRE, EXECUTOR)).verifyComplete();
        verify(planRepository).updateStatus(eq(id), eq(PlanStatus.RETIRED), any(AuditEntry.class));
    }

    @Test
    @DisplayName("control of a missing plan is 404 and an illegal move is refused before any write")
    void controlFailures() {
        ControlPlanUseCase useCase = new ControlPlanUseCase(planRepository);
        when(planRepository.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(id, ControlPlanUseCase.Action.ACTIVATE, EXECUTOR)).expectError(PlanNotFoundException.class).verify();

        when(planRepository.findById(id)).thenReturn(Mono.just(plan));
        StepVerifier.create(useCase.execute(id, ControlPlanUseCase.Action.RETIRE, EXECUTOR)).expectError(InvalidPlanStatusException.class).verify();
        verify(planRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    @DisplayName("retrieve maps a plan, the catalogue and the audit log, and answers 404 for a missing plan")
    void retrieve() {
        RetrievePlanUseCase useCase = new RetrievePlanUseCase(planRepository);
        when(planRepository.findById(id)).thenReturn(Mono.just(plan));
        when(planRepository.findAll(null)).thenReturn(Flux.just(plan));
        when(planRepository.findAll(PlanStatus.ACTIVE)).thenReturn(Flux.empty());

        StepVerifier.create(useCase.byId(id)).assertNext(r -> assertEquals("TEAM", r.code())).verifyComplete();
        StepVerifier.create(useCase.all(null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.all(PlanStatus.ACTIVE)).verifyComplete();
        StepVerifier.create(useCase.auditLog(id)).assertNext((List<AuditEntryResponse> log) -> {
            assertEquals(1, log.size());
            assertEquals("INITIATED", log.get(0).action());
        }).verifyComplete();

        when(planRepository.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.byId(id)).expectError(PlanNotFoundException.class).verify();
        StepVerifier.create(useCase.auditLog(id)).expectError(PlanNotFoundException.class).verify();
    }
}
