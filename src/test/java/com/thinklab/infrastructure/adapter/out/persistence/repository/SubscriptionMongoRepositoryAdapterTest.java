package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateSubscriptionException;
import com.thinklab.domain.exception.SubscriptionNotFoundException;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Subscription;
import com.thinklab.domain.model.Subscription.SubscriptionStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.SubscriptionDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.SubscriptionDocument.SubscriptionPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SubscriptionMongoRepositoryAdapterTest {

    private static final CodecRegistry REGISTRY = CodecRegistries.withUuidRepresentation(
            CodecRegistries.fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                    CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())),
            org.bson.UuidRepresentation.STANDARD);

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<SubscriptionDocument> collection;

    private SubscriptionMongoRepositoryAdapter adapter;
    private final UUID id = UUID.randomUUID();
    private final UUID tenant = UUID.randomUUID();
    private Subscription subscription;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("billing_db")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("subscriptions", SubscriptionDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
        adapter = new SubscriptionMongoRepositoryAdapter(mongoClient, "mongodb://localhost:27017/billing_db");
        subscription = Subscription.createNew(id, tenant, "TEAM", "admin");
    }

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, REGISTRY);
    }

    private static MongoWriteException writeError(int code, String message) {
        return new MongoWriteException(new WriteError(code, message, new BsonDocument()), new ServerAddress());
    }

    @Test
    @DisplayName("create inserts the mapped document and emits the aggregate")
    void create() {
        when(collection.insertOne(any(SubscriptionDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        StepVerifier.create(adapter.create(subscription)).expectNextMatches(saved -> saved.getId().equals(id)).verifyComplete();

        ArgumentCaptor<SubscriptionDocument> captor = ArgumentCaptor.forClass(SubscriptionDocument.class);
        verify(collection).insertOne(captor.capture());
        assertEquals("TRIALING", captor.getValue().getStatus());
        assertEquals(tenant, captor.getValue().getOrganisationId());
    }

    @Test
    @DisplayName("create maps a duplicate on the current-subscription index to DuplicateSubscriptionException (the concurrent-initiate race)")
    void createDuplicate() {
        MongoWriteException other = writeError(11000, "E11000 duplicate key error index: _id_ dup key");
        when(collection.insertOne(any(SubscriptionDocument.class)))
                .thenReturn(Mono.error(writeError(11000, "E11000 duplicate key error index: organisationId_1_current dup key")))
                .thenReturn(Mono.error(other));

        StepVerifier.create(adapter.create(subscription)).expectError(DuplicateSubscriptionException.class).verify();
        StepVerifier.create(adapter.create(subscription)).expectErrorMatches(e -> e == other).verify();
    }

    @Test
    @DisplayName("findById and findCurrentByOrganisationId map the document back, the latter only among the non-cancelled statuses")
    void finds() {
        FindPublisher<SubscriptionDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.just(SubscriptionPersistenceMapper.toDocument(subscription)))
                .thenReturn(Mono.just(SubscriptionPersistenceMapper.toDocument(subscription)))
                .thenReturn(Mono.empty()).thenReturn(Mono.empty());

        StepVerifier.create(adapter.findById(id)).expectNextMatches(s -> s.getPlanCode().equals("TEAM")).verifyComplete();
        StepVerifier.create(adapter.findCurrentByOrganisationId(tenant)).expectNextMatches(s -> s.getId().equals(id)).verifyComplete();
        StepVerifier.create(adapter.findById(id)).verifyComplete();
        StepVerifier.create(adapter.findCurrentByOrganisationId(tenant)).verifyComplete();

        ArgumentCaptor<Bson> filters = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(4)).find(filters.capture());
        String current = render(filters.getAllValues().get(1)).toJson();
        assertTrue(current.contains("TRIALING") && current.contains("ACTIVE") && current.contains("PAST_DUE") && current.contains("SUSPENDED"));
        assertTrue(!current.contains("CANCELLED"));
    }

    @Test
    @DisplayName("findAllByOrganisationId always filters by tenant and by status only when one is given")
    void findAll() {
        FindPublisher<SubscriptionDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<SubscriptionDocument> subscriber = invocation.getArgument(0);
            Flux.just(SubscriptionPersistenceMapper.toDocument(subscription)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());

        StepVerifier.create(adapter.findAllByOrganisationId(tenant, SubscriptionStatus.TRIALING)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAllByOrganisationId(tenant, null)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filters = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).find(filters.capture());
        String withStatus = render(filters.getAllValues().get(0)).toJson();
        assertTrue(withStatus.contains("organisationId") && withStatus.contains("TRIALING"));
        String tenantOnly = render(filters.getAllValues().get(1)).toJson();
        assertTrue(tenantOnly.contains("organisationId") && !tenantOnly.contains("status"));
    }

    @Test
    @DisplayName("updateStatus and updatePlan $set their field and updatedAt and $push the audit entry atomically")
    void updates() {
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        AuditEntry activated = subscription.activate("admin");
        AuditEntry planChanged = subscription.changePlan("ENTERPRISE", "admin");

        StepVerifier.create(adapter.updateStatus(id, SubscriptionStatus.ACTIVE, activated)).verifyComplete();
        StepVerifier.create(adapter.updatePlan(id, "ENTERPRISE", planChanged)).verifyComplete();

        ArgumentCaptor<Bson> updates = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).updateOne(any(Bson.class), updates.capture());
        BsonDocument status = render(updates.getAllValues().get(0));
        assertEquals("ACTIVE", status.getDocument("$set").getString("status").getValue());
        assertEquals("STATUS_CHANGED", status.getDocument("$push").getDocument("auditTrail").getString("action").getValue());
        BsonDocument plan = render(updates.getAllValues().get(1));
        assertEquals("ENTERPRISE", plan.getDocument("$set").getString("planCode").getValue());
        assertTrue(plan.getDocument("$set").containsKey("updatedAt"));
        assertEquals("PLAN_CHANGED", plan.getDocument("$push").getDocument("auditTrail").getString("action").getValue());
    }

    @Test
    @DisplayName("every partial update fails with SubscriptionNotFoundException when no document matches")
    void updatesFailWhenNothingMatches() {
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        AuditEntry entry = subscription.activate("admin");

        StepVerifier.create(adapter.updateStatus(id, SubscriptionStatus.ACTIVE, entry)).expectError(SubscriptionNotFoundException.class).verify();
        StepVerifier.create(adapter.updatePlan(id, "X", entry)).expectError(SubscriptionNotFoundException.class).verify();
    }
}
