package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;

import java.util.Objects;

/** Shared Mongo plumbing for the two adapters and the index initializer. */
final class MongoSupport {

    /** Used only when {@code mongodb.uri} names no database. */
    static final String DEFAULT_DATABASE = "thinklab_subscription_billing_db";
    static final String PLANS_COLLECTION = "plans";
    static final String SUBSCRIPTIONS_COLLECTION = "subscriptions";

    /** The driver's default registry has no POJO codec; without this every read/write fails. */
    static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build()));

    private MongoSupport() { }

    static String database(String mongoUri) {
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        return configured != null ? configured : DEFAULT_DATABASE;
    }

    /** Whether the error is a duplicate-key violation of the named index. */
    static boolean isDuplicateOn(Throwable error, String indexName) {
        return error instanceof MongoWriteException write
                && write.getError().getCategory() == ErrorCategory.DUPLICATE_KEY
                && write.getError().getMessage().contains(indexName);
    }
}
