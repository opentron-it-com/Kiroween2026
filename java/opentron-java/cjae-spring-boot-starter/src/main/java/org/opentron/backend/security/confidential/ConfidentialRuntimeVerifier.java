package org.opentron.backend.security.confidential;

/**
 * Verifies that the current runtime satisfies the configured confidential
 * computing policy. Implementations must fail closed and validate fresh,
 * challenge-bound attestation evidence before approving execution.
 */
@FunctionalInterface
public interface ConfidentialRuntimeVerifier extends ConfidentialExecutionPolicy {
    void verifyCurrentRuntime();

    @Override
    default void verifyConfidentialExecution() {
        verifyCurrentRuntime();
    }
}
