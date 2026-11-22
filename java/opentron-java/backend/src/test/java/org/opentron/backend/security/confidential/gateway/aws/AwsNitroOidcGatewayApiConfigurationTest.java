package org.opentron.backend.security.confidential.gateway.aws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.gateway.ConfidentialGatewayService;
import org.opentron.backend.security.confidential.gateway.SecureContextKey;
import org.opentron.backend.security.confidential.gateway.SecureContextConsumptionStore;
import org.opentron.backend.security.confidential.gateway.SecureContextKeyProvider;
import org.opentron.backend.security.confidential.gateway.api.ConfidentialGatewayController;
import org.opentron.backend.security.confidential.gateway.api.GatewayCredentialAuthenticator;
import org.opentron.backend.security.confidential.gateway.api.GatewayQuotaService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AwsNitroOidcGatewayApiConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    AwsNitroOidcGatewayApiConfiguration.class,
                    TestBeans.class,
                    ConfidentialGatewayController.class)
            .withPropertyValues(
                    "spring.profiles.active=aws-nitro,gateway-oidc",
                    "confidential.gateway.enabled=true");

    @Test
    void wiresAuthenticatedAwsGatewayServiceAndQuotaWhenExplicitlyEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ConfidentialGatewayService.class);
            assertThat(context).hasSingleBean(GatewayQuotaService.class);
            assertThat(context).hasSingleBean(ConfidentialGatewayController.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        GatewayCredentialAuthenticator gatewayCredentialAuthenticator() {
            return credential -> Optional.empty();
        }

        @Bean
        SecureContextKeyProvider secureContextKeyProvider() {
            return new SecureContextKeyProvider() {
                @Override
                public SecureContextKey currentKey() {
                    throw new UnsupportedOperationException(
                            "Test does not request a context key.");
                }

                @Override
                public Optional<SecureContextKey> findKey(String id) {
                    return Optional.empty();
                }
            };
        }

        @Bean
        SecureContextConsumptionStore secureContextConsumptionStore() {
            return (secureContext, expiresAt) -> true;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
