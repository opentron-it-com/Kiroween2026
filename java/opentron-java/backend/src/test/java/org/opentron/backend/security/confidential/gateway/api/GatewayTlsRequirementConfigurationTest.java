package org.opentron.backend.security.confidential.gateway.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class GatewayTlsRequirementConfigurationTest {
    private final GatewayTlsRequirementConfiguration configuration =
            new GatewayTlsRequirementConfiguration();

    @Test
    void rejectsMissingTlsConfiguration() {
        assertThrows(IllegalStateException.class,
                () -> configuration.requireTlsForConfidentialGateway(new MockEnvironment()));
    }

    @Test
    void requiresTls13AndKeystore() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.ssl.enabled", "true")
                .withProperty("server.ssl.key-store", "/run/cjae/gateway.p12")
                .withProperty("server.ssl.key-store-password", "sensitive")
                .withProperty("server.ssl.enabled-protocols", "TLSv1.3");
        assertDoesNotThrow(() -> configuration.requireTlsForConfidentialGateway(environment));
    }
}
