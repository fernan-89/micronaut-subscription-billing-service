package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import com.thinklab.domain.model.Subscription;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Creates, at startup, the two indexes the domain rules lean on (ADR-031):
 * <ul>
 *   <li>unique {@code code} on {@code plans}: a plan code names exactly one plan;</li>
 *   <li>partial unique {@code organisationId} on {@code subscriptions}, scoped to the non-cancelled statuses: an organisation has
 *       at most one current subscription, while any number of CANCELLED ones are history. A partial filter (not a plain unique
 *       index) is required exactly because those cancelled documents are expected and legal.</li>
 * </ul>
 * The use cases check first for a clean error; these indexes are the atomic backstop for concurrent writers. Fail-open like the
 * kit's initializer: errors are logged and the application still starts.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class BillingIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String PLAN_CODE_INDEX = "code_1";
    static final String CURRENT_SUBSCRIPTION_INDEX = "organisationId_1_current";

    private static final Logger log = LoggerFactory.getLogger(BillingIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;

    @Inject
    public BillingIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, TIMEOUT);
    }

    /** Test seam: how long to wait for the server. */
    BillingIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        this.database = MongoSupport.database(mongoUri);
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        ensure(MongoSupport.PLANS_COLLECTION, new Document("code", 1), new IndexOptions().unique(true).name(PLAN_CODE_INDEX));
        List<String> current = Subscription.CURRENT.stream().map(Enum::name).toList();
        ensure(MongoSupport.SUBSCRIPTIONS_COLLECTION, new Document("organisationId", 1),
                new IndexOptions().unique(true).name(CURRENT_SUBSCRIPTION_INDEX)
                        .partialFilterExpression(new Document("status", new Document("$in", current))));
    }

    private void ensure(String collection, Document keys, IndexOptions options) {
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(collection).createIndex(keys, options)).block(timeout);
            log.info("[MONGO_INDEXES] Ensured index [{}] on [{}.{}]", options.getName(), database, collection);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", options.getName(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create index [{}] on [{}.{}]: {}", options.getName(), database, collection, e.getMessage());
        }
    }
}
