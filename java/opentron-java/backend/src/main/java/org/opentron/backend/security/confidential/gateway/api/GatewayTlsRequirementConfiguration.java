package org.opentron.backend.security.confidential.gateway.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("gcp-cvm & gateway-oidc")
@ConditionalOnProperty(name = "confidential.gateway.enabled", havingValue = "true")
public class GatewayTlsRequirementConfiguration {
    @Bean
    public GatewayTlsSettings requireTlsForConfidentialGateway(Environment environment) {
        boolean tlsEnabled = environment.getProperty(
                "server.ssl.enabled", Boolean.class, false);
        String keyStore = environment.getProperty("server.ssl.key-store", "");
        String keyStorePassword = environment.getProperty(
                "server.ssl.key-store-password", "");
        String protocols = environment.getProperty(
                "server.ssl.enabled-protocols", "");
        if (!tlsEnabled || keyStore.isBlank() || keyStorePassword.isBlank()
                || !protocols.contains("TLSv1.3")) {
            throw new IllegalStateException(
                    "Confidential GCP Gateway requires TLS 1.3 and a configured keystore.");
        }
        return new GatewayTlsSettings();
    }

    public record GatewayTlsSettings() {
    }
}
