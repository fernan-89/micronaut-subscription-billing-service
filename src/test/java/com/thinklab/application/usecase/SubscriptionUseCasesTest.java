package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.SubscriptionPlanRequest;
import com.thinklab.domain.exception.DuplicateSubscriptionException;
import com.thinklab.domain.exception.InvalidSubscriptionStatusException;
import com.thinklab.domain.exception.PlanNotAvailableException;
import com.thinklab.domain.exception.SubscriptionNotFoundException;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Plan;
import com.thinklab.domain.model.Plan.PlanStatus;
import com.thinklab.domain.model.Subscription;
import com.thinklab.domain.model.Subscription.SubscriptionStatus;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.PlanRepository;
import com.thinklab.domain.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionUseCasesTest {

    private static final String EXECUTOR = "billing-admin";

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PlanRepository planRepository;
    @Mock private HashServicePort hashServicePort;

    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();
    private SubscriptionLookup lookup;
    private PlanAvailability availability;

    @BeforeEach
    void setUp() {
        lookup = new SubscriptionLookup(subscriptionRepository);
        availability = new PlanAvailability(planRepository);
    }

    private Plan plan(String code, PlanStatus status) {
        Instant now = Instant.now();
        return Plan.reconstitute(UUID.randomUUID(), code, code, Map.of("assets", 5L), status, now, now, null);
    }

    private Subscription subscription(SubscriptionStatus status, String planCode) {
        Instant now = Instant.now();
        return Subscription.reconstitute(id, org, planCode, status, now, now, null);
    }

    @Test
    @DisplayName("PlanAvailability accepts an ACTIVE plan and refuses a missing, DRAFT or RETIRED one")
    void planAvailability() {
        when(planRepository.findByCode("TEAM")).thenReturn(Mono.just(plan("TEAM", PlanStatus.ACTIVE)));
        when(planRepository.findByCode("NOPE")).thenReturn(Mono.empty());
        when(planRepository.findByCode("SOON")).thenReturn(Mono.just(plan("SOON", PlanStatus.DRAFT)));
        when(planRepository.findByCode("OLD")).thenReturn(Mono.just(plan("OLD", PlanStatus.RETIRED)));

        StepVerifier.create(availability.requireOnSale("TEAM")).verifyComplete();
        StepVerifier.create(availability.requireOnSale("NOPE")).expectError(PlanNotAvailableException.class).verify();
        StepVerifier.create(availability.requireOnSale("SOON")).expectErrorMatches(e -> e instanceof PlanNotAvailableException && e.getMessage().contains("DRAFT")).verify();
        StepVerifier.create(availability.requireOnSale("OLD")).expectError(PlanNotAvailableException.class).verify();
    }

    @Test
    @DisplayName("SubscriptionLookup answers a foreign tenant's subscription exactly like a missing one")
    void lookup() {
        when(subscriptionRepository.findById(id)).thenReturn(Mono.just(subscription(SubscriptionStatus.ACTIVE, "TEAM")));

        StepVerifier.create(lookup.owned(id, org)).expectNextCount(1).verifyComplete();
        StepVerifier.create(lookup.owned(id, UUID.randomUUID())).expectError(SubscriptionNotFoundException.class).verify();
        when(subscriptionRepository.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(lookup.owned(id, org)).expectError(SubscriptionNotFoundException.class).verify();
    }

    @Test
    @DisplayName("initiate subscribes an organisation without a current subscription to an ACTIVE plan")
    void initiate() {
        InitiateSubscriptionUseCase useCase = new InitiateSubscriptionUseCase(hashServicePort, subscriptionRepository, availability);
        when(planRepository.findByCode("TEAM")).thenReturn(Mono.just(plan("TEAM", PlanStatus.ACTIVE)));
        when(subscriptionRepository.findCurrentByOrganisationId(org)).thenReturn(Mono.empty());
        when(hashServicePort.generateSovereignId("subscription-creation")).thenReturn(Mono.just(id));
        when(subscriptionRepository.create(any(Subscription.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));

        StepVerifier.create(useCase.execute(org, new SubscriptionPlanRequest("TEAM"), EXECUTOR))
                .assertNext(response -> {
                    assertEquals(id, response.id());
                    assertEquals("TRIALING", response.status());
                    assertEquals("TEAM", response.planCode());
                    assertEquals(org, response.organisationId());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("initiate is refused for an unavailable plan or an organisation that already subscribed, before spending an id")
    void initiateRefusals() {
        InitiateSubscriptionUseCase useCase = new InitiateSubscriptionUseCase(hashServicePort, subscriptionRepository, availability);
        when(planRepository.findByCode("SOON")).thenReturn(Mono.just(plan("SOON", PlanStatus.DRAFT)));
        StepVerifier.create(useCase.execute(org, new SubscriptionPlanRequest("SOON"), EXECUTOR)).expectError(PlanNotAvailableException.class).verify();

        when(planRepository.findByCode("TEAM")).thenReturn(Mono.just(plan("TEAM", PlanStatus.ACTIVE)));
        when(subscriptionRepository.findCurrentByOrganisationId(org)).thenReturn(Mono.just(subscription(SubscriptionStatus.ACTIVE, "TEAM")));
        StepVerifier.create(useCase.execute(org, new SubscriptionPlanRequest("TEAM"), EXECUTOR))
                .expectErrorMatches(e -> e instanceof DuplicateSubscriptionException && e.getMessage().contains("ACTIVE")).verify();

        verify(hashServicePort, never()).generateSovereignId(any());
        verify(subscriptionRepository, never()).create(any());
    }

    @Test
    @DisplayName("changing plan persists the new plan code with the PLAN_CHANGED entry")
    void changePlan() {
        ChangeSubscriptionPlanUseCase useCase = new ChangeSubscriptionPlanUseCase(lookup, subscriptionRepository, availability);
        when(subscriptionRepository.findById(id)).thenReturn(Mono.just(subscription(SubscriptionStatus.ACTIVE, "TEAM")));
        when(planRepository.findByCode("ENTERPRISE")).thenReturn(Mono.just(plan("ENTERPRISE", PlanStatus.ACTIVE)));
        when(subscriptionRepository.updatePlan(eq(id), eq("ENTERPRISE"), any(AuditEntry.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(id, org, new SubscriptionPlanRequest("ENTERPRISE"), EXECUTOR)).verifyComplete();

        verify(subscriptionRepository).updatePlan(eq(id), eq("ENTERPRISE"), any(AuditEntry.class));
    }

    @Test
    @DisplayName("changing plan is refused for a foreign tenant, an unavailable plan, a cancelled subscription and the same plan, never writing")
    void changePlanRefusals() {
        ChangeSubscriptionPlanUseCase useCase = new ChangeSubscriptionPlanUseCase(lookup, subscriptionRepository, availability);
        when(subscriptionRepository.findById(id)).thenReturn(Mono.just(subscription(SubscriptionStatus.ACTIVE, "TEAM")));
        StepVerifier.create(useCase.execute(id, UUID.randomUUID(), new SubscriptionPlanRequest("ENTERPRISE"), EXECUTOR))
                .expectError(SubscriptionNotFoundException.class).verify();

        when(planRepository.findByCode("OLD")).thenReturn(Mono.just(plan("OLD", PlanStatus.RETIRED)));
        StepVerifier.create(useCase.execute(id, org, new SubscriptionPlanRequest("OLD"), EXECUTOR)).expectError(PlanNotAvailableException.class).verify();

        when(planRepository.findByCode("TEAM")).thenReturn(Mono.just(plan("TEAM", PlanStatus.ACTIVE)));
        StepVerifier.create(useCase.execute(id, org, new SubscriptionPlanRequest("TEAM"), EXECUTOR)).expectError(InvalidSubscriptionStatusException.class).verify();

        when(subscriptionRepository.findById(id)).thenReturn(Mono.just(subscription(SubscriptionStatus.CANCELLED, "TEAM")));
        when(planRepository.findByCode("ENTERPRISE")).thenReturn(Mono.just(plan("ENTERPRISE", PlanStatus.ACTIVE)));
        StepVerifier.create(useCase.execute(id, org, new SubscriptionPlanRequest("ENTERPRISE"), EXECUTOR)).expectError(InvalidSubscriptionStatusException.class).verify();

        verify(subscriptionRepository, never()).updatePlan(any(), any(), any());
    }

    @Test
    @DisplayName("control persists the target status with its audit entry for every action")
    void control() {
        ControlSubscriptionUseCase useCase = new ControlSubscriptionUseCase(lookup, subscriptionRepository);
        when(subscriptionRepository.updateStatus(eq(id), any(SubscriptionStatus.class), any(AuditEntry.class))).thenReturn(Mono.empty());

        when(subscriptionRepository.findById(id)).thenReturn(Mono.just(subscription(SubscriptionStatus.TRIALING, "TEAM")));
        StepVerifier.create(useCase.execute(id, org, ControlSubscriptionUseCase.Action.ACTIVATE, EXECUTOR)).verifyComplete();
        verify(subscriptionRepository).updateStatus(eq(id), eq(SubscriptionStatus.ACTIVE), any(AuditEntry.class));

        when(subscriptionRepository.findById(id)).thenReturn(Mono.just(subscription(SubscriptionStatus.ACTIVE, "TEAM")));
        StepVerifier.create(useCase.execute(id, org, ControlSubscriptionUseCase.Action.MARK_PAST_DUE, EXECUTOR)).verifyComplete();
        verify(subscriptionRepository).updateStatus(eq(id), eq(SubscriptionStatus.PAST_DUE), any(AuditEntry.class));

        when(subscriptionRepository.findById(id)).thenReturn(Mono.just(subscription(SubscriptionStatus.ACTIVE, "TEAM")));
        StepVerifier.create(useCase.execute(id, org, ControlSubscriptionUseCase.Action.SUSPEND, EXECUTOR)).verifyComplete();
        verify(subscriptionRepository).updateStatus(eq(id), eq(SubscriptionStatus.SUSPENDED), any(AuditEntry.class));

        when(subscriptionRepository.findById(id)).thenReturn(Mono.just(subscription(SubscriptionStatus.ACTIVE, "TEAM")));
        StepVerifier.create(useCase.execute(id, org, ControlSubscriptionUseCase.Action.CANCEL, EXECUTOR)).verifyComplete();
        verify(subscriptionRepository).updateStatus(eq(id), eq(SubscriptionStatus.CANCELLED), any(AuditEntry.class));
    }

    @Test
    @DisplayName("control is refused for a foreign tenant and for an illegal move, never writing")
    void controlRefusals() {
        ControlSubscriptionUseCase useCase = new ControlSubscriptionUseCase(lookup, subscriptionRepository);
        when(subscriptionRepository.findById(id)).thenReturn(Mono.just(subscription(SubscriptionStatus.CANCELLED, "TEAM")));

        StepVerifier.create(useCase.execute(id, UUID.randomUUID(), ControlSubscriptionUseCase.Action.ACTIVATE, EXECUTOR))
                .expectError(SubscriptionNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(id, org, ControlSubscriptionUseCase.Action.ACTIVATE, EXECUTOR))
                .expectError(InvalidSubscriptionStatusException.class).verify();
        verify(subscriptionRepository, never()).updateStatus(any(), any(), any());
    }

    @Test
    @DisplayName("retrieve maps by id, the tenant's list, the current subscription and the audit log, all tenant-scoped")
    void retrieve() {
        RetrieveSubscriptionUseCase useCase = new RetrieveSubscriptionUseCase(lookup, subscriptionRepository);
        Subscription active = subscription(SubscriptionStatus.ACTIVE, "TEAM");
        when(subscriptionRepository.findById(id)).thenReturn(Mono.just(active));
        when(subscriptionRepository.findAllByOrganisationId(org, null)).thenReturn(Flux.just(active));
        when(subscriptionRepository.findAllByOrganisationId(org, SubscriptionStatus.CANCELLED)).thenReturn(Flux.empty());
        when(subscriptionRepository.findCurrentByOrganisationId(org)).thenReturn(Mono.just(active));

        StepVerifier.create(useCase.byId(id, org)).assertNext(r -> assertEquals("TEAM", r.planCode())).verifyComplete();
        StepVerifier.create(useCase.byId(id, UUID.randomUUID())).expectError(SubscriptionNotFoundException.class).verify();
        StepVerifier.create(useCase.all(org, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.all(org, SubscriptionStatus.CANCELLED)).verifyComplete();
        StepVerifier.create(useCase.current(org)).assertNext(r -> assertEquals("ACTIVE", r.status())).verifyComplete();
        StepVerifier.create(useCase.auditLog(id, org)).assertNext(log -> assertTrue(log.isEmpty())).verifyComplete();
        StepVerifier.create(useCase.auditLog(id, UUID.randomUUID())).expectError(SubscriptionNotFoundException.class).verify();

        when(subscriptionRepository.findCurrentByOrganisationId(org)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.current(org)).expectError(SubscriptionNotFoundException.class).verify();
    }

    @Test
    @DisplayName("entitlements: a subscription decides through its plan, PAST_DUE is a grace period, SUSPENDED allows nothing")
    void entitlementFromSubscription() {
        EvaluateEntitlementUseCase useCase = new EvaluateEntitlementUseCase(subscriptionRepository, planRepository, "HOMELAB");
        when(planRepository.findByCode("TEAM")).thenReturn(Mono.just(plan("TEAM", PlanStatus.ACTIVE)));

        when(subscriptionRepository.findCurrentByOrganisationId(org)).thenReturn(Mono.just(subscription(SubscriptionStatus.ACTIVE, "TEAM")));
        StepVerifier.create(useCase.execute(org, "assets")).assertNext(r -> {
            assertTrue(r.allowed());
            assertEquals(5L, r.limit());
            assertEquals("SUBSCRIPTION", r.source());
            assertEquals("TEAM", r.planCode());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(org, "sso")).assertNext(r -> assertEquals(false, r.allowed())).verifyComplete();

        when(subscriptionRepository.findCurrentByOrganisationId(org)).thenReturn(Mono.just(subscription(SubscriptionStatus.PAST_DUE, "TEAM")));
        StepVerifier.create(useCase.execute(org, "assets")).assertNext(r -> assertTrue(r.allowed())).verifyComplete();

        when(subscriptionRepository.findCurrentByOrganisationId(org)).thenReturn(Mono.just(subscription(SubscriptionStatus.SUSPENDED, "TEAM")));
        StepVerifier.create(useCase.execute(org, "assets")).assertNext(r -> {
            assertEquals(false, r.allowed());
            assertEquals("SUSPENDED", r.source());
            assertEquals("TEAM", r.planCode());
        }).verifyComplete();
    }

    @Test
    @DisplayName("entitlements: a subscription whose plan vanished is UNMANAGED (fail-open)")
    void entitlementMissingPlan() {
        EvaluateEntitlementUseCase useCase = new EvaluateEntitlementUseCase(subscriptionRepository, planRepository, "HOMELAB");
        when(subscriptionRepository.findCurrentByOrganisationId(org)).thenReturn(Mono.just(subscription(SubscriptionStatus.ACTIVE, "GONE")));
        when(planRepository.findByCode("GONE")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(org, "assets")).assertNext(r -> {
            assertTrue(r.allowed());
            assertEquals("UNMANAGED", r.source());
        }).verifyComplete();
    }

    @Test
    @DisplayName("entitlements: no subscription falls back to the ACTIVE default plan, else UNMANAGED (fail-open)")
    void entitlementDefaultPlan() {
        EvaluateEntitlementUseCase useCase = new EvaluateEntitlementUseCase(subscriptionRepository, planRepository, "HOMELAB");
        when(subscriptionRepository.findCurrentByOrganisationId(org)).thenReturn(Mono.empty());

        when(planRepository.findByCode("HOMELAB")).thenReturn(Mono.just(plan("HOMELAB", PlanStatus.ACTIVE)));
        StepVerifier.create(useCase.execute(org, "assets")).assertNext(r -> {
            assertEquals(5L, r.limit());
            assertEquals("DEFAULT_PLAN", r.source());
            assertEquals("HOMELAB", r.planCode());
        }).verifyComplete();

        when(planRepository.findByCode("HOMELAB")).thenReturn(Mono.just(plan("HOMELAB", PlanStatus.DRAFT)));
        StepVerifier.create(useCase.execute(org, "assets")).assertNext(r -> assertEquals("UNMANAGED", r.source())).verifyComplete();

        when(planRepository.findByCode("HOMELAB")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(org, "assets")).assertNext(r -> {
            assertTrue(r.allowed());
            assertEquals("UNMANAGED", r.source());
            assertEquals(null, r.planCode());
        }).verifyComplete();
    }
}
