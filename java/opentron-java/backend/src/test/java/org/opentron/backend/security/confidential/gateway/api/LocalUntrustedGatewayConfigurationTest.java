package org.opentron.backend.security.confidential.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.gateway.ConfidentialGatewayService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalUntrustedGatewayConfigurationTest {
    private static final String API_TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String CONTEXT_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    LocalUntrustedGatewayConfiguration.class,
                    ConfidentialGatewayController.class,
                    ConfidentialGatewayApiExceptionHandler.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void createsGatewayOnlyWhenExplicitlyEnabledOnLoopback() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local-untrusted",
                        "confidential.gateway.enabled=true",
                        "server.address=127.0.0.1",
                        "CONFIDENTIAL_GATEWAY_API_TOKEN=" + API_TOKEN,
                        "CONFIDENTIAL_GATEWAY_CONTEXT_KEY=" + CONTEXT_KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ConfidentialGatewayService.class);
                    assertThat(context).hasSingleBean(ConfidentialGatewayController.class);
                });
    }

    @Test
    void rejectsNonLoopbackBinding() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local-untrusted",
                        "confidential.gateway.enabled=true",
                        "server.address=0.0.0.0",
                        "CONFIDENTIAL_GATEWAY_API_TOKEN=" + API_TOKEN,
                        "CONFIDENTIAL_GATEWAY_CONTEXT_KEY=" + CONTEXT_KEY)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "The local-untrusted Gateway may only bind to a loopback address.");
                });
    }

    @Test
    void leavesGatewayDisabledWithoutOptInProfileAndFlag() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ConfidentialGatewayController.class);
            assertThat(context).doesNotHaveBean(ConfidentialGatewayService.class);
        });
    }
}
