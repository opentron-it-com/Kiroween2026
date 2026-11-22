package org.opentron.backend.security.confidential.gateway.api;

import java.util.Optional;

@FunctionalInterface
public interface GatewayCredentialAuthenticator {
    Optional<GatewayPrincipal> authenticate(String bearerCredential);
}
