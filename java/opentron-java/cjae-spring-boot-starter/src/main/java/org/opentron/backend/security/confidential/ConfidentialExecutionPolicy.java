package org.opentron.backend.security.confidential;

/**
 * Validates that confidential execution is permitted for the current call.
 */
@FunctionalInterface
public interface ConfidentialExecutionPolicy {
    void verifyConfidentialExecution();
}
