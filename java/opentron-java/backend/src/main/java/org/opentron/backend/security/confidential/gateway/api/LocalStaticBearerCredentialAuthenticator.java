package org.opentron.backend.security.confidential.gateway.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;

public final class LocalStaticBearerCredentialAuthenticator
        implements GatewayCredentialAuthenticator {
    private final byte[] expectedToken;
    private final GatewayPrincipal principal;

    public LocalStaticBearerCredentialAuthenticator(String token, String tenantId) {
        if (token == null || token.length() < 32) {
            throw new IllegalArgumentException(
                    "Gateway API token must contain at least 32 characters.");
        }
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
        this.principal = new GatewayPrincipal(
                "local-static-bearer",
                tenantId,
                Set.of("gateway:sanitize", "gateway:desanitize"));
    }

    @Override
    public Optional<GatewayPrincipal> authenticate(String bearerCredential) {
        if (bearerCredential == null) {
            return Optional.empty();
        }
        byte[] suppliedToken = bearerCredential.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, suppliedToken)
                ? Optional.of(principal)
                : Optional.empty();
    }
}
