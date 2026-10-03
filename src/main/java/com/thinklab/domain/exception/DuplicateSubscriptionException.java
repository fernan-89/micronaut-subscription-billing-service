package com.thinklab.domain.exception;

/**
 * Domain Exception: Thrown when an Subscription is initiated while the organisation already has a non-cancelled subscription
 * (at most one per organisation).
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict.
 */
public class DuplicateSubscriptionException extends BusinessException {

    private static final String ERROR_CODE = "ERR-SUB-00409";

    public DuplicateSubscriptionException(String message) {
        super(ERROR_CODE, message);
    }
}
