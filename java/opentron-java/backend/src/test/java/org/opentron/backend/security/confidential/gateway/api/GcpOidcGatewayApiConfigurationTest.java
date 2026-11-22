package org.opentron.backend.security.confidential.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.gateway.ConfidentialGatewayService;
import org.opentron.backend.security.confidential.gateway.SecureContextKey;
import org.opentron.backend.security.confidential.gateway.SecureContextKeyProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GcpOidcGatewayApiConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    GcpOidcGatewayApiConfiguration.class,
                    ConfidentialGatewayController.class,
                    ConfidentialGatewayApiExceptionHandler.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(
                    SecureContextKeyProvider.class,
                    () -> new SecureContextKeyProvider() {
                        private final SecureContextKey key = new SecureContextKey(
                                "test-key",
                                new SecretKeySpec(new byte[32], "AES"));

                        @Override
                        public SecureContextKey currentKey() {
                            return key;
                        }

                        @Override
                        public java.util.Optional<SecureContextKey> findKey(String id) {
                            return key.id().equals(id)
                                    ? java.util.Optional.of(key)
                                    : java.util.Optional.empty();
                        }
                    })
            .withBean(
                    GatewayCredentialAuthenticator.class,
                    () -> credential -> java.util.Optional.empty());

    @Test
    void createsGcpBackedGatewayOnlyWhenGcpOidcAndExplicitEnablementArePresent() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=gcp-cvm,gateway-oidc",
                        "confidential.gateway.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ConfidentialGatewayService.class);
                    assertThat(context).hasSingleBean(ConfidentialGatewayController.class);
                    assertThat(context).hasSingleBean(GatewayQuotaService.class);
                });
    }

    @Test
    void doesNotCreateGcpGatewayWithoutBothProfilesAndExplicitEnablement() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=gcp-cvm",
                        "confidential.gateway.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ConfidentialGatewayService.class);
                    assertThat(context).doesNotHaveBean(ConfidentialGatewayController.class);
                });

        contextRunner
                .withPropertyValues("spring.profiles.active=gcp-cvm,gateway-oidc")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ConfidentialGatewayService.class);
                    assertThat(context).doesNotHaveBean(ConfidentialGatewayController.class);
                });
    }
}
