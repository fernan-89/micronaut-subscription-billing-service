package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.InitiatePlanRequest;
import com.thinklab.application.dto.request.UpdatePlanRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.PlanResponse;
import com.thinklab.application.usecase.ControlPlanUseCase;
import com.thinklab.application.usecase.InitiatePlanUseCase;
import com.thinklab.application.usecase.RetrievePlanUseCase;
import com.thinklab.application.usecase.UpdatePlanUseCase;
import com.thinklab.domain.exception.InvalidPlanStatusException;
import com.thinklab.domain.exception.PlanNotFoundException;
import com.thinklab.domain.model.Plan.PlanStatus;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanControllerTest {

    private static final String EXECUTOR = "billing-admin";

    @Mock private InitiatePlanUseCase initiatePlanUseCase;
    @Mock private UpdatePlanUseCase updatePlanUseCase;
    @Mock private ControlPlanUseCase controlPlanUseCase;
    @Mock private RetrievePlanUseCase retrievePlanUseCase;

    @InjectMocks private PlanController controller;

    private final UUID id = UUID.randomUUID();
    private PlanResponse sample;

    @BeforeEach
    void setUp() {
        sample = new PlanResponse(id, "TEAM", "Team", Map.of("assets", 5L), "DRAFT", Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("initiate answers 201 Created with the drafted plan")
    void initiate() {
        InitiatePlanRequest request = new InitiatePlanRequest("TEAM", "Team", Map.of("assets", 5L));
        when(initiatePlanUseCase.execute(request, EXECUTOR)).thenReturn(Mono.just(sample));

        StepVerifier.create(controller.initiate(EXECUTOR, request)).assertNext(response -> {
            assertEquals(HttpStatus.CREATED, response.getStatus());
            assertEquals(id, response.body().id());
        }).verifyComplete();
    }

    @Test
    @DisplayName("retrieve by id answers 200 and propagates a missing plan")
    void retrieveById() {
        when(retrievePlanUseCase.byId(id)).thenReturn(Mono.just(sample)).thenReturn(Mono.error(new PlanNotFoundException(id)));

        StepVerifier.create(controller.retrieveById(id)).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieveById(id)).expectError(PlanNotFoundException.class).verify();
    }

    @Test
    @DisplayName("retrieve lists the catalogue, optionally by status")
    void retrieveAll() {
        when(retrievePlanUseCase.all(null)).thenReturn(Flux.just(sample));
        when(retrievePlanUseCase.all(PlanStatus.ACTIVE)).thenReturn(Flux.empty());

        StepVerifier.create(controller.retrieveAll(null)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
        StepVerifier.create(controller.retrieveAll(PlanStatus.ACTIVE)).assertNext(list -> assertEquals(0, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("update answers 204 and propagates an illegal edit")
    void update() {
        UpdatePlanRequest request = new UpdatePlanRequest("Team 2", Map.of());
        when(updatePlanUseCase.execute(id, request, EXECUTOR)).thenReturn(Mono.empty()).thenReturn(Mono.error(new InvalidPlanStatusException("no")));

        StepVerifier.create(controller.update(id, EXECUTOR, request)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.update(id, EXECUTOR, request)).expectError(InvalidPlanStatusException.class).verify();
    }

    @Test
    @DisplayName("control/activate and control/retire answer 204 through the matching action")
    void control() {
        when(controlPlanUseCase.execute(eq(id), eq(ControlPlanUseCase.Action.ACTIVATE), eq(EXECUTOR))).thenReturn(Mono.empty());
        when(controlPlanUseCase.execute(eq(id), eq(ControlPlanUseCase.Action.RETIRE), eq(EXECUTOR))).thenReturn(Mono.empty());

        StepVerifier.create(controller.controlActivate(id, EXECUTOR)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlRetire(id, EXECUTOR)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        verify(controlPlanUseCase).execute(id, ControlPlanUseCase.Action.ACTIVATE, EXECUTOR);
        verify(controlPlanUseCase).execute(id, ControlPlanUseCase.Action.RETIRE, EXECUTOR);
    }

    @Test
    @DisplayName("audit-log/retrieve returns the ledger")
    void auditLog() {
        AuditEntryResponse entry = new AuditEntryResponse(Instant.now(), "INITIATED", EXECUTOR, null, "DRAFT", "d");
        when(retrievePlanUseCase.auditLog(id)).thenReturn(Mono.just(List.of(entry)));

        StepVerifier.create(controller.retrieveAuditLog(id)).assertNext(list -> assertEquals("INITIATED", list.get(0).action())).verifyComplete();
    }
}
