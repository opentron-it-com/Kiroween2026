package org.opentron.backend.security.confidential;

public final class ConfidentialExecutionDeniedException extends SecurityException {
    public ConfidentialExecutionDeniedException(String message) {
        super(message);
    }

    public ConfidentialExecutionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
