package org.opentron.backend.security.confidential.gateway.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class OidcJwtGatewayCredentialAuthenticatorTest {
    private static final String ISSUER = "https://identity.example.test/";
    private static final String AUDIENCE = "confidential-gateway";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void createsPrincipalOnlyFromValidIssuerAudienceAndClaims() {
        var authenticator = authenticator(jwt(
                Map.of(
                        "tenant_id", "tenant-a",
                        "scope", "openid gateway:sanitize gateway:desanitize profile"),
                NOW.plusSeconds(60),
                null,
                ISSUER,
                List.of(AUDIENCE)));

        var principal = authenticator.authenticate("signed-token");

        assertTrue(principal.isPresent());
        assertEquals("subject-a", principal.get().subject());
        assertEquals("tenant-a", principal.get().tenantId());
        assertEquals(
                java.util.Set.of("gateway:sanitize", "gateway:desanitize"),
                principal.get().scopes());
    }

    @Test
    void rejectsWrongIssuerAudienceExpiredOrNotYetValidTokens() {
        assertRejected(jwt(Map.of("tenant_id", "tenant-a", "scope", "gateway:sanitize"),
                NOW.plusSeconds(60), null, "https://untrusted.example/", List.of(AUDIENCE)));
        assertRejected(jwt(Map.of("tenant_id", "tenant-a", "scope", "gateway:sanitize"),
                NOW.plusSeconds(60), null, ISSUER, List.of("other-service")));
        assertRejected(jwt(Map.of("tenant_id", "tenant-a", "scope", "gateway:sanitize"),
                NOW, null, ISSUER, List.of(AUDIENCE)));
        assertRejected(jwt(Map.of("tenant_id", "tenant-a", "scope", "gateway:sanitize"),
                NOW.plusSeconds(60), NOW.plusSeconds(1), ISSUER, List.of(AUDIENCE)));
    }

    @Test
    void rejectsMissingTenantAndMalformedScopeClaims() {
        assertRejected(jwt(Map.of("scope", "gateway:sanitize"),
                NOW.plusSeconds(60), null, ISSUER, List.of(AUDIENCE)));
        assertRejected(jwt(Map.of("tenant_id", "tenant-a", "scope", 123),
                NOW.plusSeconds(60), null, ISSUER, List.of(AUDIENCE)));
    }

    @Test
    void tokensWithoutScopeClaimsReceiveNoGatewayPermissions() {
        var authenticator = new OidcJwtGatewayCredentialAuthenticator(
                token -> jwt(Map.of(), NOW.plusSeconds(60), null, ISSUER, List.of(AUDIENCE)),
                ISSUER,
                AUDIENCE,
                "tenant_id",
                "scope",
                Clock.fixed(NOW, ZoneOffset.UTC));

        var principal = authenticator.authenticate("signed-token");

        assertFalse(principal.isPresent());
    }

    @Test
    void rejectsTokensThatCannotBeDecoded() {
        JwtDecoder decoder = token -> {
            throw new org.springframework.security.oauth2.jwt.JwtException("invalid signature");
        };
        assertFalse(authenticator(decoder).authenticate("bad-token").isPresent());
        assertFalse(authenticator(decoder).authenticate(" ").isPresent());
    }

    private static void assertRejected(Jwt jwt) {
        assertFalse(authenticator(jwt).authenticate("signed-token").isPresent());
    }

    private static OidcJwtGatewayCredentialAuthenticator authenticator(Jwt jwt) {
        return authenticator(token -> jwt);
    }

    private static OidcJwtGatewayCredentialAuthenticator authenticator(JwtDecoder decoder) {
        return new OidcJwtGatewayCredentialAuthenticator(
                decoder,
                ISSUER,
                AUDIENCE,
                "tenant_id",
                "scope",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Jwt jwt(
            Map<String, Object> claims,
            Instant expiresAt,
            Instant notBefore,
            String issuer,
            List<String> audience) {
        var builder = Jwt.withTokenValue("signed-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .subject("subject-a")
                .audience(audience)
                .issuedAt(NOW.minusSeconds(60))
                .expiresAt(expiresAt)
                .claims(values -> values.putAll(claims));
        if (notBefore != null) {
            builder.claim("nbf", notBefore);
        }
        return builder.build();
    }
}
