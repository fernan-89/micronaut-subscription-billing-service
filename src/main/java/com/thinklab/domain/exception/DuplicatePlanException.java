package com.thinklab.domain.exception;

/**
 * Domain Exception: the plan code is already taken.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict (ADR-019).
 */
public class DuplicatePlanException extends BusinessException {

    private static final String ERROR_CODE = "ERR-SUB-00409";

    public DuplicatePlanException(String message) {
        super(ERROR_CODE, message);
    }
}
