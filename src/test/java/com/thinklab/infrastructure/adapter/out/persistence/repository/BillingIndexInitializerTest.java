package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class BillingIndexInitializerTest {

    private final StartupEvent startup = mock(StartupEvent.class);

    private MongoCollection<Document> collectionIn(MongoClient client, MongoDatabase database, String name) {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase(any())).thenReturn(database);
        when(database.getCollection(name)).thenReturn(collection);
        return collection;
    }

    @Test
    @DisplayName("startup creates the unique plan-code index and the partial unique current-subscription index, in the database named by mongodb.uri")
    void createsTheIndexes() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> plans = collectionIn(client, database, "plans");
        MongoCollection<Document> subscriptions = collectionIn(client, database, "subscriptions");
        when(plans.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("code_1"));
        when(subscriptions.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("organisationId_1_current"));

        new BillingIndexInitializer(client, "mongodb://mongo:27017/billing").onApplicationEvent(startup);

        verify(client, org.mockito.Mockito.atLeastOnce()).getDatabase("billing");
        ArgumentCaptor<Document> planKeys = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<IndexOptions> planOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(plans).createIndex(planKeys.capture(), planOptions.capture());
        assertEquals(new Document("code", 1), planKeys.getValue());
        assertTrue(planOptions.getValue().isUnique());
        assertEquals(BillingIndexInitializer.PLAN_CODE_INDEX, planOptions.getValue().getName());

        ArgumentCaptor<Document> subKeys = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<IndexOptions> subOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(subscriptions).createIndex(subKeys.capture(), subOptions.capture());
        assertEquals(new Document("organisationId", 1), subKeys.getValue());
        assertTrue(subOptions.getValue().isUnique());
        assertEquals(BillingIndexInitializer.CURRENT_SUBSCRIPTION_INDEX, subOptions.getValue().getName());
        Document partial = (Document) subOptions.getValue().getPartialFilterExpression();
        List<String> in = (List<String>) ((Document) partial.get("status")).get("$in");
        assertEquals(4, in.size());
        assertTrue(in.contains("TRIALING") && in.contains("SUSPENDED") && !in.contains("CANCELLED"));
    }

    @Test
    @DisplayName("a URI without a database uses the service default")
    void defaultDatabase() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> plans = collectionIn(client, database, "plans");
        MongoCollection<Document> subscriptions = collectionIn(client, database, "subscriptions");
        when(plans.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("x"));
        when(subscriptions.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("y"));

        new BillingIndexInitializer(client, "mongodb://mongo:27017").onApplicationEvent(startup);

        verify(client, org.mockito.Mockito.atLeastOnce()).getDatabase(MongoSupport.DEFAULT_DATABASE);
    }

    @Test
    @DisplayName("fail-open: an unreachable server or a rejected index is logged, never propagated, and the next index is still tried")
    void failOpen() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> plans = collectionIn(client, database, "plans");
        MongoCollection<Document> subscriptions = collectionIn(client, database, "subscriptions");
        when(plans.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.error(new MongoTimeoutException("no server")));
        when(subscriptions.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.error(new IllegalStateException("E11000 existing duplicates")));

        assertDoesNotThrow(() -> new BillingIndexInitializer(client, "mongodb://mongo:27017/b", Duration.ofSeconds(1)).onApplicationEvent(startup));

        verify(subscriptions).createIndex(any(), any(IndexOptions.class));
    }

    @Test
    @DisplayName("collaborators, mongodb.uri and the startup event are null-checked")
    void guards() {
        MongoClient client = mock(MongoClient.class);
        assertThrows(NullPointerException.class, () -> new BillingIndexInitializer(null, "mongodb://mongo:27017/a"));
        assertThrows(NullPointerException.class, () -> new BillingIndexInitializer(client, null));
        assertThrows(NullPointerException.class, () -> new BillingIndexInitializer(client, "mongodb://mongo:27017/a").onApplicationEvent(null));
    }
}
