package org.opentron.backend.security.confidential.local;

import org.opentron.backend.security.confidential.ConfidentialExecutionDeniedException;
import org.opentron.backend.security.confidential.ConfidentialExecutionPolicy;

public final class LocalEncryptedRequestExecutionPolicy implements ConfidentialExecutionPolicy {
    @Override
    public void verifyConfidentialExecution() {
        if (LocalConfidentialRequestContext.currentPermit() == null) {
            throw new ConfidentialExecutionDeniedException(
                    "Confidential execution requires a validated local encrypted request.");
        }
    }
}
