package org.opentron.cjae.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.gateway.aws.LinuxVsockHealthBridge;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AwsNitroHealthVsockConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AwsNitroHealthVsockConfiguration.class)
            .withBean(AwsNitroStartupStatus.class, () -> new AwsNitroStartupStatus(true))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withInitializer(context ->
                    context.getEnvironment().setActiveProfiles("aws-nitro"));

    @Test
    void startsHealthOnlyBridgeAfterRequiredVerificationWhenGatewayIsDisabled() {
        contextRunner
                .withPropertyValues(
                        "confidential.aws.nitro.health-vsock.enabled=true",
                        "confidential.aws.nitro.verify-on-startup=true",
                        "confidential.gateway.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(LinuxVsockHealthBridge.class);
                    assertThat(context).hasSingleBean(ApplicationRunner.class);
                });
    }

    @Test
    void doesNotStartHealthBridgeWhenVerificationIsDisabled() {
        contextRunner
                .withPropertyValues(
                        "confidential.aws.nitro.health-vsock.enabled=true",
                        "confidential.aws.nitro.verify-on-startup=false",
                        "confidential.gateway.enabled=false")
                .run(context ->
                        assertThat(context).doesNotHaveBean(LinuxVsockHealthBridge.class));
    }

    @Test
    void doesNotStartHealthBridgeWhenGatewayIsEnabled() {
        contextRunner
                .withPropertyValues(
                        "confidential.aws.nitro.health-vsock.enabled=true",
                        "confidential.aws.nitro.verify-on-startup=true",
                        "confidential.gateway.enabled=true")
                .run(context ->
                        assertThat(context).doesNotHaveBean(LinuxVsockHealthBridge.class));
    }
}
