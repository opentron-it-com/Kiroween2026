package org.opentron.backend.security.confidential.gateway.api;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

public final class OidcJwtGatewayCredentialAuthenticator
        implements GatewayCredentialAuthenticator {
    private static final Set<String> GATEWAY_SCOPES =
            Set.of("gateway:sanitize", "gateway:desanitize");

    private final JwtDecoder jwtDecoder;
    private final String issuer;
    private final String audience;
    private final String tenantClaim;
    private final String scopesClaim;
    private final Clock clock;

    public OidcJwtGatewayCredentialAuthenticator(
            JwtDecoder jwtDecoder,
            String issuer,
            String audience,
            String tenantClaim,
            String scopesClaim,
            Clock clock) {
        if (jwtDecoder == null || isBlank(issuer) || isBlank(audience)
                || isBlank(tenantClaim) || isBlank(scopesClaim)
                || clock == null) {
            throw new IllegalArgumentException("OIDC gateway authentication settings are invalid.");
        }
        this.jwtDecoder = jwtDecoder;
        this.issuer = issuer;
        this.audience = audience;
        this.tenantClaim = tenantClaim;
        this.scopesClaim = scopesClaim;
        this.clock = clock;
    }

    @Override
    public Optional<GatewayPrincipal> authenticate(String bearerCredential) {
        if (bearerCredential == null || bearerCredential.isBlank()) {
            return Optional.empty();
        }

        final Jwt jwt;
        try {
            jwt = jwtDecoder.decode(bearerCredential);
        } catch (JwtException e) {
            return Optional.empty();
        }

        String subject = jwt.getSubject();
        Object tenantValue = jwt.getClaims().get(tenantClaim);
        String tenantId = tenantValue instanceof String value ? value : null;
        Instant now = clock.instant();
        Instant expiresAt = jwt.getExpiresAt();
        Instant notBefore = jwt.getNotBefore();
        if (subject == null || subject.isBlank()
                || tenantId == null || tenantId.isBlank()
                || jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())
                || !jwt.getAudience().contains(audience)
                || expiresAt == null || !now.isBefore(expiresAt)
                || (notBefore != null && now.isBefore(notBefore))) {
            return Optional.empty();
        }

        Optional<Set<String>> scopes = readScopes(jwt.getClaims().get(scopesClaim));
        if (scopes.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new GatewayPrincipal(subject, tenantId, scopes.get()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Set<String>> readScopes(Object value) {
        if (value instanceof String scopeString) {
            return Optional.of(scopeString.isBlank()
                    ? Set.of()
                    : recognizedScopes(List.of(scopeString.trim().split("\\s+"))));
        }
        if (value instanceof Collection<?> scopeValues) {
            var scopeNames = new LinkedHashSet<String>();
            for (Object scope : scopeValues) {
                if (!(scope instanceof String scopeName) || scopeName.isBlank()) {
                    return Optional.empty();
                }
                scopeNames.add(scopeName);
            }
            return Optional.of(recognizedScopes(scopeNames));
        }
        return Optional.empty();
    }

    private static Set<String> recognizedScopes(Collection<String> values) {
        var recognized = new LinkedHashSet<String>();
        for (String value : values) {
            if (GATEWAY_SCOPES.contains(value)) {
                recognized.add(value);
            }
        }
        return Set.copyOf(recognized);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
