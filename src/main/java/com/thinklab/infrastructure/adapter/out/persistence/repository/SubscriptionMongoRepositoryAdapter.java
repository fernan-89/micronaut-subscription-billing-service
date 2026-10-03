package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateSubscriptionException;
import com.thinklab.domain.exception.SubscriptionNotFoundException;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Subscription;
import com.thinklab.domain.model.Subscription.SubscriptionStatus;
import com.thinklab.domain.repository.SubscriptionRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.SubscriptionDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.SubscriptionDocument.SubscriptionPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.conversions.Bson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for Subscriptions. Every transition is one atomic {@code $set} + {@code $push} that also
 * appends the forensic audit entry. The partial unique index on {@code organisationId} for non-cancelled statuses
 * ({@link BillingIndexInitializer}) makes "one current subscription per organisation" hold under concurrent initiation.
 */
@Singleton
public class SubscriptionMongoRepositoryAdapter implements SubscriptionRepository {

    private static final List<String> CURRENT_STATUSES = Subscription.CURRENT.stream().map(Enum::name).toList();

    private final MongoClient mongoClient;
    private final String database;

    public SubscriptionMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        this.database = MongoSupport.database(mongoUri);
    }

    private MongoCollection<SubscriptionDocument> collection() {
        return mongoClient.getDatabase(database).getCollection(MongoSupport.SUBSCRIPTIONS_COLLECTION, SubscriptionDocument.class)
                .withCodecRegistry(MongoSupport.POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<Subscription> create(Subscription subscription) {
        return Mono.from(collection().insertOne(SubscriptionPersistenceMapper.toDocument(subscription)))
                .map(result -> subscription)
                .onErrorMap(e -> MongoSupport.isDuplicateOn(e, BillingIndexInitializer.CURRENT_SUBSCRIPTION_INDEX),
                        e -> new DuplicateSubscriptionException("Organisation " + subscription.getOrganisationId() + " already has a current subscription."));
    }

    @Override
    public Mono<Subscription> findById(UUID id) {
        return Mono.from(collection().find(Filters.eq("_id", id)).first()).map(SubscriptionPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Subscription> findCurrentByOrganisationId(UUID organisationId) {
        Bson filter = Filters.and(Filters.eq("organisationId", organisationId), Filters.in("status", CURRENT_STATUSES));
        return Mono.from(collection().find(filter).first()).map(SubscriptionPersistenceMapper::toDomain);
    }

    @Override
    public Flux<Subscription> findAllByOrganisationId(UUID organisationId, SubscriptionStatus status) {
        Bson filter = status == null
                ? Filters.eq("organisationId", organisationId)
                : Filters.and(Filters.eq("organisationId", organisationId), Filters.eq("status", status.name()));
        return Flux.from(collection().find(filter).sort(Sorts.descending("createdAt"))).map(SubscriptionPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> updateStatus(UUID id, SubscriptionStatus status, AuditEntry auditEntry) {
        return executeUpdate(id, Updates.combine(
                Updates.set("status", status.name()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))));
    }

    @Override
    public Mono<Void> updatePlan(UUID id, String planCode, AuditEntry auditEntry) {
        return executeUpdate(id, Updates.combine(
                Updates.set("planCode", planCode),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))));
    }

    private Mono<Void> executeUpdate(UUID id, Bson update) {
        return Mono.from(collection().updateOne(Filters.eq("_id", id), update))
                .flatMap(result -> result.getMatchedCount() == 0 ? Mono.error(new SubscriptionNotFoundException(id)) : Mono.empty());
    }
}
