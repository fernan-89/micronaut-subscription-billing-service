package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.SubscriptionPlanRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.EntitlementResponse;
import com.thinklab.application.dto.response.SubscriptionResponse;
import com.thinklab.application.usecase.ChangeSubscriptionPlanUseCase;
import com.thinklab.application.usecase.ControlSubscriptionUseCase;
import com.thinklab.application.usecase.EvaluateEntitlementUseCase;
import com.thinklab.application.usecase.InitiateSubscriptionUseCase;
import com.thinklab.application.usecase.RetrieveSubscriptionUseCase;
import com.thinklab.domain.exception.DuplicateSubscriptionException;
import com.thinklab.domain.exception.SubscriptionNotFoundException;
import com.thinklab.domain.model.Subscription.SubscriptionStatus;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionControllerTest {

    private static final String EXECUTOR = "billing-admin";

    @Mock private InitiateSubscriptionUseCase initiateSubscriptionUseCase;
    @Mock private ChangeSubscriptionPlanUseCase changeSubscriptionPlanUseCase;
    @Mock private ControlSubscriptionUseCase controlSubscriptionUseCase;
    @Mock private RetrieveSubscriptionUseCase retrieveSubscriptionUseCase;
    @Mock private EvaluateEntitlementUseCase evaluateEntitlementUseCase;

    @InjectMocks private SubscriptionController controller;

    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();
    private SubscriptionResponse sample;

    @BeforeEach
    void setUp() {
        sample = new SubscriptionResponse(id, org, "TEAM", "TRIALING", Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("initiate answers 201 Created and propagates a duplicate")
    void initiate() {
        SubscriptionPlanRequest request = new SubscriptionPlanRequest("TEAM");
        when(initiateSubscriptionUseCase.execute(org, request, EXECUTOR)).thenReturn(Mono.just(sample))
                .thenReturn(Mono.error(new DuplicateSubscriptionException("dup")));

        StepVerifier.create(controller.initiate(org.toString(), EXECUTOR, request)).assertNext(response -> {
            assertEquals(HttpStatus.CREATED, response.getStatus());
            assertEquals(id, response.body().id());
        }).verifyComplete();
        StepVerifier.create(controller.initiate(org.toString(), EXECUTOR, request)).expectError(DuplicateSubscriptionException.class).verify();
    }

    @Test
    @DisplayName("retrieve by id is tenant-scoped and answers 200, propagating a 404")
    void retrieveById() {
        when(retrieveSubscriptionUseCase.byId(id, org)).thenReturn(Mono.just(sample)).thenReturn(Mono.error(new SubscriptionNotFoundException(id)));

        StepVerifier.create(controller.retrieveById(id, org.toString())).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieveById(id, org.toString())).expectError(SubscriptionNotFoundException.class).verify();
    }

    @Test
    @DisplayName("retrieve lists the tenant's subscriptions, optionally by status")
    void retrieveAll() {
        when(retrieveSubscriptionUseCase.all(org, null)).thenReturn(Flux.just(sample));
        when(retrieveSubscriptionUseCase.all(org, SubscriptionStatus.CANCELLED)).thenReturn(Flux.empty());

        StepVerifier.create(controller.retrieveAll(org.toString(), null)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
        StepVerifier.create(controller.retrieveAll(org.toString(), SubscriptionStatus.CANCELLED)).assertNext(list -> assertEquals(0, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("current/retrieve answers 200 with the current subscription and 404 when there is none")
    void current() {
        when(retrieveSubscriptionUseCase.current(org)).thenReturn(Mono.just(sample)).thenReturn(Mono.error(new SubscriptionNotFoundException("none")));

        StepVerifier.create(controller.retrieveCurrent(org.toString())).assertNext(r -> assertEquals("TEAM", r.body().planCode())).verifyComplete();
        StepVerifier.create(controller.retrieveCurrent(org.toString())).expectError(SubscriptionNotFoundException.class).verify();
    }

    @Test
    @DisplayName("plan/update answers 204")
    void updatePlan() {
        SubscriptionPlanRequest request = new SubscriptionPlanRequest("ENTERPRISE");
        when(changeSubscriptionPlanUseCase.execute(id, org, request, EXECUTOR)).thenReturn(Mono.empty());

        StepVerifier.create(controller.updatePlan(id, org.toString(), EXECUTOR, request)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("every control/* route answers 204 through its action")
    void control() {
        for (ControlSubscriptionUseCase.Action action : ControlSubscriptionUseCase.Action.values()) {
            when(controlSubscriptionUseCase.execute(id, org, action, EXECUTOR)).thenReturn(Mono.empty());
        }

        StepVerifier.create(controller.controlActivate(id, org.toString(), EXECUTOR)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlMarkPastDue(id, org.toString(), EXECUTOR)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlSuspend(id, org.toString(), EXECUTOR)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlCancel(id, org.toString(), EXECUTOR)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        for (ControlSubscriptionUseCase.Action action : ControlSubscriptionUseCase.Action.values()) {
            verify(controlSubscriptionUseCase).execute(id, org, action, EXECUTOR);
        }
    }

    @Test
    @DisplayName("audit-log/retrieve returns the tenant-scoped ledger")
    void auditLog() {
        AuditEntryResponse entry = new AuditEntryResponse(Instant.now(), "INITIATED", EXECUTOR, null, "TRIALING", "d");
        when(retrieveSubscriptionUseCase.auditLog(id, org)).thenReturn(Mono.just(List.of(entry)));

        StepVerifier.create(controller.retrieveAuditLog(id, org.toString())).assertNext(list -> assertEquals("INITIATED", list.get(0).action())).verifyComplete();
    }

    @Test
    @DisplayName("entitlement/evaluate answers 200 with the decision")
    void evaluate() {
        when(evaluateEntitlementUseCase.execute(org, "assets")).thenReturn(Mono.just(new EntitlementResponse("assets", true, 5L, "SUBSCRIPTION", "TEAM")));

        StepVerifier.create(controller.evaluateEntitlement(org.toString(), "assets")).assertNext(r -> {
            assertEquals(HttpStatus.OK, r.getStatus());
            assertEquals(5L, r.body().limit());
        }).verifyComplete();
    }
}
