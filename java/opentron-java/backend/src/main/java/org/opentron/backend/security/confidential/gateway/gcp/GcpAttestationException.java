package org.opentron.backend.security.confidential.gateway.gcp;

public final class GcpAttestationException extends RuntimeException {
    public GcpAttestationException(String message) {
        super(message);
    }

    public GcpAttestationException(String message, Throwable cause) {
        super(message, cause);
    }
}
