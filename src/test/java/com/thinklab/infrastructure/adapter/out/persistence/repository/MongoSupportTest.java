package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import org.bson.BsonDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoSupportTest {

    private static MongoWriteException writeError(int code, String message) {
        return new MongoWriteException(new WriteError(code, message, new BsonDocument()), new ServerAddress());
    }

    @Test
    @DisplayName("the database is the one named in mongodb.uri, the default when none is named, and the URI is mandatory")
    void database() {
        assertEquals("billing", MongoSupport.database("mongodb://mongo:27017/billing"));
        assertEquals(MongoSupport.DEFAULT_DATABASE, MongoSupport.database("mongodb://mongo:27017"));
        assertThrows(NullPointerException.class, () -> MongoSupport.database(null));
    }

    @Test
    @DisplayName("only a duplicate-key error naming the given index counts as that duplicate")
    void duplicateDetection() {
        assertTrue(MongoSupport.isDuplicateOn(writeError(11000, "E11000 duplicate key error index: code_1 dup key"), "code_1"));
        assertFalse(MongoSupport.isDuplicateOn(writeError(11000, "E11000 duplicate key error index: _id_ dup key"), "code_1"));
        assertFalse(MongoSupport.isDuplicateOn(writeError(121, "Document failed validation index: code_1"), "code_1"));
        assertFalse(MongoSupport.isDuplicateOn(new IllegalStateException("code_1"), "code_1"));
    }

    @Test
    @DisplayName("the POJO codec registry is available and the class is a utility class")
    void utility() throws Exception {
        assertNotNull(MongoSupport.POJO_CODEC_REGISTRY);
        Constructor<MongoSupport> constructor = MongoSupport.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }
}
