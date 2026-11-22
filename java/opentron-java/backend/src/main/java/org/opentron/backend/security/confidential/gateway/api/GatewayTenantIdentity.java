package org.opentron.backend.security.confidential.gateway.api;

public record GatewayTenantIdentity(String id) {
    public GatewayTenantIdentity {
        if (id == null || id.isBlank() || id.length() > 256) {
            throw new IllegalArgumentException("Gateway tenant identifier is invalid.");
        }
    }
}
