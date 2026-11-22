package org.opentron.backend.security.confidential.gateway.api;

public interface GatewayQuotaService {
    void checkAndConsume(GatewayPrincipal principal, int textCharacters);
}
