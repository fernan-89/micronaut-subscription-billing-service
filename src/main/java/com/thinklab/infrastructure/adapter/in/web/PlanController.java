package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.InitiatePlanRequest;
import com.thinklab.application.dto.request.UpdatePlanRequest;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.PlanResponse;
import com.thinklab.application.usecase.ControlPlanUseCase;
import com.thinklab.application.usecase.InitiatePlanUseCase;
import com.thinklab.application.usecase.RetrievePlanUseCase;
import com.thinklab.application.usecase.UpdatePlanUseCase;
import com.thinklab.domain.model.Plan.PlanStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the Plan aggregate of the {@code subscription-billing} Service Domain. Plans are platform-wide, so
 * there is no {@code X-Tenant-Id}; routes sit under {@code /subscription-billing/v1/plan} (the secondary aggregate's prefix,
 * like {@code workflow-approval}'s {@code policy/}). No {@code DELETE}: no physical delete exists in this Service Domain.
 */
@Controller("/subscription-billing/v1/plan")
public class PlanController {

    private static final Logger log = LoggerFactory.getLogger(PlanController.class);
    static final String EXECUTOR_HEADER = "X-Executor";

    private final InitiatePlanUseCase initiatePlanUseCase;
    private final UpdatePlanUseCase updatePlanUseCase;
    private final ControlPlanUseCase controlPlanUseCase;
    private final RetrievePlanUseCase retrievePlanUseCase;

    public PlanController(InitiatePlanUseCase initiatePlanUseCase, UpdatePlanUseCase updatePlanUseCase,
                          ControlPlanUseCase controlPlanUseCase, RetrievePlanUseCase retrievePlanUseCase) {
        this.initiatePlanUseCase = initiatePlanUseCase;
        this.updatePlanUseCase = updatePlanUseCase;
        this.controlPlanUseCase = controlPlanUseCase;
        this.retrievePlanUseCase = retrievePlanUseCase;
    }

    /** Behavior Qualifier: {@code initiate}. Drafts a new Plan. */
    @Post("/initiate")
    public Mono<HttpResponse<PlanResponse>> initiate(@Header(EXECUTOR_HEADER) @NotBlank String executor, @Body @Valid InitiatePlanRequest request) {
        log.info("[ACTION: INITIATE_PLAN] [EXECUTOR: {}] Received request to draft plan: {}", executor, request.code());

        return initiatePlanUseCase.execute(request, executor).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. Fetches a single Plan by UUID. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<PlanResponse>> retrieveById(@PathVariable UUID id) {
        log.info("[ACTION: RETRIEVE_PLAN] Received request to get plan by ID: {}", id);

        return retrievePlanUseCase.byId(id).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection). Lists the plan catalogue, optionally by status. */
    @Get("/retrieve")
    public Mono<List<PlanResponse>> retrieveAll(@QueryValue @Nullable PlanStatus status) {
        log.info("[ACTION: RETRIEVE_PLANS] Received request to list plans, status: {}", status);

        return Mono.defer(() -> retrievePlanUseCase.all(status).collectList());
    }

    /** Behavior Qualifier: {@code update}. Replaces name and entitlements; only legal in DRAFT. */
    @Put("/{id}/update")
    public Mono<HttpResponse<Void>> update(@PathVariable UUID id, @Header(EXECUTOR_HEADER) @NotBlank String executor,
                                           @Body @Valid UpdatePlanRequest request) {
        log.info("[ACTION: UPDATE_PLAN] [EXECUTOR: {}] Received request to update plan ID: {}", executor, id);

        return updatePlanUseCase.execute(id, request, executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/activate}. DRAFT -> ACTIVE. */
    @Put("/{id}/control/activate")
    public Mono<HttpResponse<Void>> controlActivate(@PathVariable UUID id, @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, ControlPlanUseCase.Action.ACTIVATE, executor);
    }

    /** Behavior Qualifier: {@code control/retire}. ACTIVE -> RETIRED. */
    @Put("/{id}/control/retire")
    public Mono<HttpResponse<Void>> controlRetire(@PathVariable UUID id, @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, ControlPlanUseCase.Action.RETIRE, executor);
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. Immutable forensic ledger of the Plan. */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> retrieveAuditLog(@PathVariable UUID id) {
        log.info("[ACTION: RETRIEVE_PLAN_AUDIT_LOG] Received request for audit ledger of plan ID: {}", id);

        return retrievePlanUseCase.auditLog(id);
    }

    private Mono<HttpResponse<Void>> control(UUID id, ControlPlanUseCase.Action action, String executor) {
        log.info("[ACTION: CONTROL_PLAN] [EXECUTOR: {}] {} for ID: {}", executor, action, id);

        return controlPlanUseCase.execute(id, action, executor).thenReturn(HttpResponse.noContent());
    }
}
