package org.opentron.backend.security.confidential.gateway.api;

import java.net.URI;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSAlgorithmFamilyJWSKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
@Profile("gateway-oidc")
public class GatewayOidcAuthenticationConfiguration {
    private static final int MAX_PINNED_JWKS_BYTES = 64 * 1024;
    private static final int MAX_PINNED_JWKS_KEYS = 32;

    @Bean
    public JwtDecoder gatewayJwtDecoder(
            Environment environment,
            @Value("${confidential.gateway.oidc.issuer}") String issuer,
            @Value("${confidential.gateway.oidc.jwks-uri:}") String jwksUri,
            @Value("${confidential.gateway.oidc.jwks-json:}") String jwksJson) {
        requireHttpsUri(issuer, "confidential.gateway.oidc.issuer");
        if (environment.acceptsProfiles(Profiles.of("aws-nitro"))) {
            if (jwksJson.isBlank()) {
                throw new IllegalStateException(
                        "AWS Nitro requires a pinned confidential.gateway.oidc.jwks-json because the enclave has no internet access.");
            }
            return staticJwksDecoder(issuer, jwksJson);
        }
        if (!jwksJson.isBlank()) {
            return staticJwksDecoder(issuer, jwksJson);
        }
        requireHttpsUri(jwksUri, "confidential.gateway.oidc.jwks-uri");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    private static JwtDecoder staticJwksDecoder(String issuer, String serializedJwks) {
        if (serializedJwks.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > MAX_PINNED_JWKS_BYTES) {
            throw new IllegalStateException("Pinned JWKS exceeds its 64 KiB size limit.");
        }
        final JWKSet jwkSet;
        try {
            jwkSet = JWKSet.parse(serializedJwks);
        } catch (java.text.ParseException exception) {
            throw new IllegalStateException(
                    "confidential.gateway.oidc.jwks-json is not a valid JWKS.", exception);
        }
        Set<String> keyIds = new HashSet<>();
        for (JWK key : jwkSet.getKeys()) {
            KeyType keyType = key.getKeyType();
            if (key.isPrivate()
                    || !(KeyType.RSA.equals(keyType)
                            || KeyType.EC.equals(keyType)
                            || KeyType.OKP.equals(keyType))
                    || key.getKeyID() == null
                    || key.getKeyID().isBlank()
                    || !keyIds.add(key.getKeyID())) {
                throw new IllegalStateException(
                        "JWKS must contain unique key IDs and public asymmetric signing keys only.");
            }
        }
        if (jwkSet.getKeys().isEmpty() || jwkSet.getKeys().size() > MAX_PINNED_JWKS_KEYS) {
            throw new IllegalStateException(
                    "JWKS must contain between 1 and 32 public signing keys.");
        }

        ImmutableJWKSet<SecurityContext> source = new ImmutableJWKSet<>(jwkSet);
        try {
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(
                    JWSAlgorithmFamilyJWSKeySelector.fromJWKSource(source));
            NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
            return decoder;
        } catch (com.nimbusds.jose.KeySourceException exception) {
            throw new IllegalStateException("Unable to create the pinned JWKS selector.", exception);
        }
    }

    @Bean
    public GatewayCredentialAuthenticator oidcGatewayCredentialAuthenticator(
            JwtDecoder gatewayJwtDecoder,
            @Value("${confidential.gateway.oidc.issuer}") String issuer,
            @Value("${confidential.gateway.oidc.audience}") String audience,
            @Value("${confidential.gateway.oidc.tenant-claim:tenant_id}") String tenantClaim,
            @Value("${confidential.gateway.oidc.scopes-claim:scope}") String scopesClaim) {
        return new OidcJwtGatewayCredentialAuthenticator(
                gatewayJwtDecoder,
                issuer,
                audience,
                tenantClaim,
                scopesClaim,
                Clock.systemUTC());
    }

    private static void requireHttpsUri(String raw, String property) {
        final URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(property + " must be an absolute HTTPS URI.", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException(property + " must be an absolute HTTPS URI.");
        }
    }
}
