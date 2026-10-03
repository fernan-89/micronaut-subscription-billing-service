package com.thinklab.domain.exception;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain Exception: a requested {@link com.thinklab.domain.model.Plan} could not be resolved.
 *
 * <p>RFC 7807 mapping: HTTP 404 Not Found (one 404 code for the whole Service Domain).
 */
public class PlanNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-SUB-00404";

    public PlanNotFoundException(UUID id) {
        super(ERROR_CODE, String.format("Plan with sovereign ID [%s] could not be found in the system of record.",
                Objects.requireNonNull(id, "Domain Exception constraint violated: UUID cannot be null.")));
    }

    public PlanNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
