package org.opentron.backend.security.confidential.gateway;

public final class SecureContextException extends RuntimeException {
    public SecureContextException() {
        super("Secure context is invalid, expired, or unavailable.");
    }
}
