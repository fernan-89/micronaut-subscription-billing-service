package com.thinklab.domain.exception;

/**
 * Domain Exception: a subscription was pointed at a plan that does not exist or is not ACTIVE (a DRAFT is not on sale yet, a
 * RETIRED one no longer is).
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict (ADR-019) - the request is well formed but the plan catalogue's state refuses it.
 */
public class PlanNotAvailableException extends BusinessException {

    private static final String ERROR_CODE = "ERR-SUB-00409";

    public PlanNotAvailableException(String message) {
        super(ERROR_CODE, message);
    }
}
