package org.opentron.backend.security.confidential.local;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.ConfidentialExecutionDeniedException;

class LocalEncryptedRequestExecutionPolicyTest {
    @Test
    void deniesExecutionWithoutAValidatedRequestPermit() {
        LocalEncryptedRequestExecutionPolicy policy = new LocalEncryptedRequestExecutionPolicy();

        assertThrows(ConfidentialExecutionDeniedException.class, policy::verifyConfidentialExecution);
    }

    @Test
    void allowsExecutionOnlyWithinTheValidatedPermitScope() {
        LocalEncryptedRequestExecutionPolicy policy = new LocalEncryptedRequestExecutionPolicy();
        LocalConfidentialRequestContext.Permit permit =
                LocalConfidentialRequestContext.createPermit("test-key");

        LocalConfidentialRequestContext.runWith(permit, () ->
                assertDoesNotThrow(policy::verifyConfidentialExecution));

        assertThrows(ConfidentialExecutionDeniedException.class, policy::verifyConfidentialExecution);
    }
}
