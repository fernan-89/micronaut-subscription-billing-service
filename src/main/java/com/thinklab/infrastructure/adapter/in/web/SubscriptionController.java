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
import com.thinklab.domain.model.Subscription.SubscriptionStatus;
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
 * Inbound Web Adapter for the {@code subscription-billing} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.Subscription} is the Control Record, every
 * route follows {@code /subscription-billing/v1/{control-record-id}/{behavior-qualifier}} and every route is tenant-scoped.
 * There is no {@code DELETE}. The route other services (and the web app) call is {@code GET entitlement/evaluate}.
 */
@Controller("/subscription-billing/v1")
public class SubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";

    private final InitiateSubscriptionUseCase initiateSubscriptionUseCase;
    private final ChangeSubscriptionPlanUseCase changeSubscriptionPlanUseCase;
    private final ControlSubscriptionUseCase controlSubscriptionUseCase;
    private final RetrieveSubscriptionUseCase retrieveSubscriptionUseCase;
    private final EvaluateEntitlementUseCase evaluateEntitlementUseCase;

    public SubscriptionController(InitiateSubscriptionUseCase initiateSubscriptionUseCase,
                                  ChangeSubscriptionPlanUseCase changeSubscriptionPlanUseCase,
                                  ControlSubscriptionUseCase controlSubscriptionUseCase,
                                  RetrieveSubscriptionUseCase retrieveSubscriptionUseCase,
                                  EvaluateEntitlementUseCase evaluateEntitlementUseCase) {
        this.initiateSubscriptionUseCase = initiateSubscriptionUseCase;
        this.changeSubscriptionPlanUseCase = changeSubscriptionPlanUseCase;
        this.controlSubscriptionUseCase = controlSubscriptionUseCase;
        this.retrieveSubscriptionUseCase = retrieveSubscriptionUseCase;
        this.evaluateEntitlementUseCase = evaluateEntitlementUseCase;
    }

    /** Behavior Qualifier: {@code initiate}. Starts the organisation's subscription (TRIALING). */
    @Post("/initiate")
    public Mono<HttpResponse<SubscriptionResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Body @Valid SubscriptionPlanRequest request
    ) {
        log.info("[ACTION: INITIATE_SUBSCRIPTION] [EXECUTOR: {}] Received request to subscribe organisation: {} to plan: {}",
                executor, tenantId, request.planCode());

        return initiateSubscriptionUseCase.execute(UUID.fromString(tenantId), request, executor).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. Fetches a single Subscription by UUID, scoped to the tenant. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<SubscriptionResponse>> retrieveById(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId) {
        log.info("[ACTION: RETRIEVE_SUBSCRIPTION] Received request to get subscription by ID: {}", id);

        return retrieveSubscriptionUseCase.byId(id, UUID.fromString(tenantId)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection). The tenant's subscriptions, newest first. */
    @Get("/retrieve")
    public Mono<List<SubscriptionResponse>> retrieveAll(@Header(TENANT_HEADER) @NotBlank String tenantId,
                                                        @QueryValue @Nullable SubscriptionStatus status) {
        log.info("[ACTION: RETRIEVE_SUBSCRIPTIONS] Received request to list subscriptions for organisation: {} status: {}", tenantId, status);

        return Mono.defer(() -> retrieveSubscriptionUseCase.all(UUID.fromString(tenantId), status).collectList());
    }

    /** Behavior Qualifier: {@code current/retrieve}. The tenant's one non-cancelled subscription (404 when it has none). */
    @Get("/current/retrieve")
    public Mono<HttpResponse<SubscriptionResponse>> retrieveCurrent(@Header(TENANT_HEADER) @NotBlank String tenantId) {
        log.info("[ACTION: RETRIEVE_CURRENT_SUBSCRIPTION] Received request for organisation: {}", tenantId);

        return Mono.defer(() -> retrieveSubscriptionUseCase.current(UUID.fromString(tenantId))).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code plan/update}. Moves the subscription to another (ACTIVE) plan. */
    @Put("/{id}/plan/update")
    public Mono<HttpResponse<Void>> updatePlan(
            @PathVariable UUID id,
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Body @Valid SubscriptionPlanRequest request
    ) {
        log.info("[ACTION: UPDATE_SUBSCRIPTION_PLAN] [EXECUTOR: {}] Received request to move subscription ID: {} to plan: {}", executor, id, request.planCode());

        return changeSubscriptionPlanUseCase.execute(id, UUID.fromString(tenantId), request, executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/activate}. TRIALING, PAST_DUE or SUSPENDED -> ACTIVE. */
    @Put("/{id}/control/activate")
    public Mono<HttpResponse<Void>> controlActivate(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                    @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, tenantId, ControlSubscriptionUseCase.Action.ACTIVATE, executor);
    }

    /** Behavior Qualifier: {@code control/mark-past-due}. ACTIVE -> PAST_DUE. */
    @Put("/{id}/control/mark-past-due")
    public Mono<HttpResponse<Void>> controlMarkPastDue(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                       @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, tenantId, ControlSubscriptionUseCase.Action.MARK_PAST_DUE, executor);
    }

    /** Behavior Qualifier: {@code control/suspend}. ACTIVE or PAST_DUE -> SUSPENDED. */
    @Put("/{id}/control/suspend")
    public Mono<HttpResponse<Void>> controlSuspend(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                   @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, tenantId, ControlSubscriptionUseCase.Action.SUSPEND, executor);
    }

    /** Behavior Qualifier: {@code control/cancel}. Any non-cancelled status -> CANCELLED (terminal). */
    @Put("/{id}/control/cancel")
    public Mono<HttpResponse<Void>> controlCancel(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                  @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        return control(id, tenantId, ControlSubscriptionUseCase.Action.CANCEL, executor);
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. Immutable forensic ledger of the Subscription. */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> retrieveAuditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId) {
        log.info("[ACTION: RETRIEVE_SUBSCRIPTION_AUDIT_LOG] Received request for audit ledger of subscription ID: {}", id);

        return retrieveSubscriptionUseCase.auditLog(id, UUID.fromString(tenantId));
    }

    /** Behavior Qualifier: {@code entitlement/evaluate}. May this tenant use the feature, and up to what limit? */
    @Get("/entitlement/evaluate")
    public Mono<HttpResponse<EntitlementResponse>> evaluateEntitlement(@Header(TENANT_HEADER) @NotBlank String tenantId,
                                                                       @QueryValue @NotBlank String feature) {
        log.info("[ACTION: EVALUATE_ENTITLEMENT] Received request for feature [{}], organisation: {}", feature, tenantId);

        return Mono.defer(() -> evaluateEntitlementUseCase.execute(UUID.fromString(tenantId), feature)).map(HttpResponse::ok);
    }

    private Mono<HttpResponse<Void>> control(UUID id, String tenantId, ControlSubscriptionUseCase.Action action, String executor) {
        log.info("[ACTION: CONTROL_SUBSCRIPTION] [EXECUTOR: {}] {} for ID: {}", executor, action, id);

        return controlSubscriptionUseCase.execute(id, UUID.fromString(tenantId), action, executor).thenReturn(HttpResponse.noContent());
    }
}
