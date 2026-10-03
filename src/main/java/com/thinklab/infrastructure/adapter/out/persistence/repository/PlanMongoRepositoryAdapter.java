package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicatePlanException;
import com.thinklab.domain.exception.PlanNotFoundException;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Plan;
import com.thinklab.domain.model.Plan.PlanStatus;
import com.thinklab.domain.repository.PlanRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PlanDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PlanDocument.PlanPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.conversions.Bson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for Plans. Every transition is one atomic {@code $set} + {@code $push} that also appends
 * the forensic audit entry, so state and ledger can never diverge. The unique index on {@code code}
 * ({@link BillingIndexInitializer}) is what makes a duplicate code impossible, even under concurrent creation.
 */
@Singleton
public class PlanMongoRepositoryAdapter implements PlanRepository {

    private final MongoClient mongoClient;
    private final String database;

    public PlanMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        this.database = MongoSupport.database(mongoUri);
    }

    private MongoCollection<PlanDocument> collection() {
        return mongoClient.getDatabase(database).getCollection(MongoSupport.PLANS_COLLECTION, PlanDocument.class)
                .withCodecRegistry(MongoSupport.POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<Plan> create(Plan plan) {
        return Mono.from(collection().insertOne(PlanPersistenceMapper.toDocument(plan)))
                .map(result -> plan)
                .onErrorMap(e -> MongoSupport.isDuplicateOn(e, BillingIndexInitializer.PLAN_CODE_INDEX),
                        e -> new DuplicatePlanException("A plan with code " + plan.getCode() + " already exists."));
    }

    @Override
    public Mono<Plan> findById(UUID id) {
        return Mono.from(collection().find(Filters.eq("_id", id)).first()).map(PlanPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Plan> findByCode(String code) {
        return Mono.from(collection().find(Filters.eq("code", code)).first()).map(PlanPersistenceMapper::toDomain);
    }

    @Override
    public Flux<Plan> findAll(PlanStatus status) {
        Bson filter = status == null ? Filters.empty() : Filters.eq("status", status.name());
        return Flux.from(collection().find(filter).sort(Sorts.ascending("code"))).map(PlanPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> updateContent(UUID id, String name, Map<String, Long> entitlements, AuditEntry auditEntry) {
        return executeUpdate(id, Updates.combine(
                Updates.set("name", name),
                Updates.set("entitlements", entitlements),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))));
    }

    @Override
    public Mono<Void> updateStatus(UUID id, PlanStatus status, AuditEntry auditEntry) {
        return executeUpdate(id, Updates.combine(
                Updates.set("status", status.name()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))));
    }

    private Mono<Void> executeUpdate(UUID id, Bson update) {
        return Mono.from(collection().updateOne(Filters.eq("_id", id), update))
                .flatMap(result -> result.getMatchedCount() == 0 ? Mono.error(new PlanNotFoundException(id)) : Mono.empty());
    }
}
