package org.opentron.backend.security.confidential.gateway.api;

public final class GatewayQuotaExceededException extends RuntimeException {
    public GatewayQuotaExceededException(String message) {
        super(message);
    }
}
