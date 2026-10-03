package com.thinklab.domain.exception;

/**
 * Domain Exception: an illegal lifecycle move or edit on a {@link com.thinklab.domain.model.Plan}.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict (ADR-019).
 */
public class InvalidPlanStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-SUB-00409";

    public InvalidPlanStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
