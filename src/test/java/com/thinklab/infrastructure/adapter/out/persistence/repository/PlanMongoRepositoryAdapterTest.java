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
import com.thinklab.domain.exception.DuplicatePlanException;
import com.thinklab.domain.exception.PlanNotFoundException;
import com.thinklab.domain.model.AuditEntry;
import com.thinklab.domain.model.Plan;
import com.thinklab.domain.model.Plan.PlanStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PlanDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.PlanDocument.PlanPersistenceMapper;
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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class PlanMongoRepositoryAdapterTest {

    private static final CodecRegistry REGISTRY = CodecRegistries.withUuidRepresentation(
            CodecRegistries.fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                    CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())),
            org.bson.UuidRepresentation.STANDARD);

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<PlanDocument> collection;

    private PlanMongoRepositoryAdapter adapter;
    private final UUID id = UUID.randomUUID();
    private Plan plan;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("billing_db")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("plans", PlanDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
        adapter = new PlanMongoRepositoryAdapter(mongoClient, "mongodb://localhost:27017/billing_db");
        plan = Plan.createNew(id, "TEAM", "Team", Map.of("assets", 5L), "admin");
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
        when(collection.insertOne(any(PlanDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        StepVerifier.create(adapter.create(plan)).expectNextMatches(saved -> saved.getId().equals(id)).verifyComplete();

        ArgumentCaptor<PlanDocument> captor = ArgumentCaptor.forClass(PlanDocument.class);
        verify(collection).insertOne(captor.capture());
        assertEquals("DRAFT", captor.getValue().getStatus());
        assertEquals("TEAM", captor.getValue().getCode());
    }

    @Test
    @DisplayName("create maps a duplicate on the plan-code index to DuplicatePlanException and propagates anything else")
    void createDuplicate() {
        MongoWriteException other = writeError(11000, "E11000 duplicate key error index: _id_ dup key");
        when(collection.insertOne(any(PlanDocument.class)))
                .thenReturn(Mono.error(writeError(11000, "E11000 duplicate key error collection: billing_db.plans index: code_1 dup key")))
                .thenReturn(Mono.error(other));

        StepVerifier.create(adapter.create(plan)).expectError(DuplicatePlanException.class).verify();
        StepVerifier.create(adapter.create(plan)).expectErrorMatches(e -> e == other).verify();
    }

    @Test
    @DisplayName("findById and findByCode map the document back, or complete empty")
    void finds() {
        FindPublisher<PlanDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.just(PlanPersistenceMapper.toDocument(plan))).thenReturn(Mono.just(PlanPersistenceMapper.toDocument(plan)))
                .thenReturn(Mono.empty()).thenReturn(Mono.empty());

        StepVerifier.create(adapter.findById(id)).expectNextMatches(p -> p.getCode().equals("TEAM")).verifyComplete();
        StepVerifier.create(adapter.findByCode("TEAM")).expectNextMatches(p -> p.getId().equals(id)).verifyComplete();
        StepVerifier.create(adapter.findById(id)).verifyComplete();
        StepVerifier.create(adapter.findByCode("NOPE")).verifyComplete();

        ArgumentCaptor<Bson> filters = ArgumentCaptor.forClass(Bson.class);
        verify(collection, org.mockito.Mockito.times(4)).find(filters.capture());
        assertTrue(render(filters.getAllValues().get(1)).toJson().contains("TEAM"));
    }

    @Test
    @DisplayName("findAll lists by code, filtering by status only when one is given")
    void findAll() {
        FindPublisher<PlanDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<PlanDocument> subscriber = invocation.getArgument(0);
            Flux.just(PlanPersistenceMapper.toDocument(plan)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());

        StepVerifier.create(adapter.findAll(PlanStatus.DRAFT)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(null)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filters = ArgumentCaptor.forClass(Bson.class);
        verify(collection, org.mockito.Mockito.times(2)).find(filters.capture());
        assertEquals("DRAFT", render(filters.getAllValues().get(0)).getString("status").getValue());
        assertTrue(render(filters.getAllValues().get(1)).isEmpty());
    }

    @Test
    @DisplayName("updateContent $sets name, entitlements and updatedAt and $pushes the audit entry atomically")
    void updateContent() {
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        AuditEntry entry = plan.updateContent("Team 2", Map.of("assets", 9L), "admin");

        StepVerifier.create(adapter.updateContent(id, "Team 2", Map.of("assets", 9L), entry)).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(any(Bson.class), update.capture());
        BsonDocument doc = render(update.getValue());
        assertEquals("Team 2", doc.getDocument("$set").getString("name").getValue());
        assertEquals(9L, doc.getDocument("$set").getDocument("entitlements").getInt64("assets").getValue());
        assertTrue(doc.getDocument("$set").containsKey("updatedAt"));
        assertEquals("UPDATED", doc.getDocument("$push").getDocument("auditTrail").getString("action").getValue());
    }

    @Test
    @DisplayName("updateStatus $sets status and $pushes a STATUS_CHANGED entry")
    void updateStatus() {
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        AuditEntry entry = plan.activate("admin");

        StepVerifier.create(adapter.updateStatus(id, PlanStatus.ACTIVE, entry)).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(any(Bson.class), update.capture());
        BsonDocument doc = render(update.getValue());
        assertEquals("ACTIVE", doc.getDocument("$set").getString("status").getValue());
        BsonDocument pushed = doc.getDocument("$push").getDocument("auditTrail");
        assertEquals("DRAFT", pushed.getString("fromStatus").getValue());
        assertEquals("ACTIVE", pushed.getString("toStatus").getValue());
    }

    @Test
    @DisplayName("every partial update fails with PlanNotFoundException when no document matches")
    void updatesFailWhenNothingMatches() {
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        AuditEntry entry = plan.activate("admin");

        StepVerifier.create(adapter.updateStatus(id, PlanStatus.ACTIVE, entry)).expectError(PlanNotFoundException.class).verify();
        StepVerifier.create(adapter.updateContent(id, "n", Map.of(), entry)).expectError(PlanNotFoundException.class).verify();
    }
}
