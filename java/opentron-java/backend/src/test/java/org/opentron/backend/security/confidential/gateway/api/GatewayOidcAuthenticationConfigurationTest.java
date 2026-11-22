package org.opentron.backend.security.confidential.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class GatewayOidcAuthenticationConfigurationTest {
    @Test
    void awsNitroVerifiesAgainstPinnedJwksWithoutFetchingTheNetworkUri() throws Exception {
        String issuer = "https://identity.example/";
        String audience = "cjae-gateway-test";
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .keyID("test-signing-key")
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
        String jwks = new JWKSet(jwk).toString();
        Instant now = Instant.now();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID("test-signing-key")
                        .build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject("tenant-user")
                        .audience(audience)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(60)))
                        .build());
        signedJwt.sign(new RSASSASigner(keyPair.getPrivate()));

        new ApplicationContextRunner()
                .withUserConfiguration(GatewayOidcAuthenticationConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=aws-nitro,gateway-oidc",
                        "confidential.gateway.oidc.issuer=" + issuer,
                        "confidential.gateway.oidc.audience=" + audience,
                        "confidential.gateway.oidc.jwks-uri=",
                        "confidential.gateway.oidc.jwks-json=" + jwks)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtDecoder decoder = context.getBean(JwtDecoder.class);
                    assertThat(decoder.decode(signedJwt.serialize()).getSubject())
                            .isEqualTo("tenant-user");
                });
    }

    @Test
    void awsNitroFailsClosedWithoutPinnedJwks() {
        new ApplicationContextRunner()
                .withUserConfiguration(GatewayOidcAuthenticationConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=aws-nitro,gateway-oidc",
                        "confidential.gateway.oidc.issuer=https://identity.example/",
                        "confidential.gateway.oidc.audience=cjae-gateway-test",
                        "confidential.gateway.oidc.jwks-uri=",
                        "confidential.gateway.oidc.jwks-json=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("requires a pinned");
                });
    }
}
