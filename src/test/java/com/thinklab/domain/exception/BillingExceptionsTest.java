package com.thinklab.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingExceptionsTest {

    @Test
    @DisplayName("a missing plan is ERR-SUB-00404, naming the id or carrying a message, and the id is mandatory")
    void planNotFound() {
        UUID id = UUID.randomUUID();

        assertEquals("ERR-SUB-00404", new PlanNotFoundException(id).getErrorCode());
        assertTrue(new PlanNotFoundException(id).getMessage().contains(id.toString()));
        assertEquals("nope", new PlanNotFoundException("nope").getMessage());
        assertThrows(NullPointerException.class, () -> new PlanNotFoundException((UUID) null));
    }

    @Test
    @DisplayName("plan conflicts are all ERR-SUB-00409")
    void conflicts() {
        assertEquals("ERR-SUB-00409", new DuplicatePlanException("d").getErrorCode());
        assertEquals("ERR-SUB-00409", new InvalidPlanStatusException("i").getErrorCode());
        assertEquals("ERR-SUB-00409", new PlanNotAvailableException("p").getErrorCode());
    }
}
