package org.opentron.backend.security.confidential.gateway.sdk;

public final class GcpAttestationException extends RuntimeException {
    public GcpAttestationException(String message) {
        super(message);
    }

    public GcpAttestationException(String message, Throwable cause) {
        super(message, cause);
    }
}
