package org.opentron.backend.security.confidential.gateway.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("aws-nitro & gateway-oidc & !gcp-cvm")
@ConditionalOnProperty(name = "confidential.gateway.enabled", havingValue = "true")
public class AwsNitroGatewayVsockConfiguration {
    @Bean(destroyMethod = "close")
    public LinuxVsockTlsBridge awsNitroGatewayVsockTlsBridge(
            @Value("${confidential.aws.nitro.gateway.vsock-port:8001}") int vsockPort,
            @Value("${confidential.aws.nitro.gateway.tls-port:8443}") int tlsPort) {
        return new LinuxVsockTlsBridge(vsockPort, tlsPort);
    }

    @Bean
    public ApplicationRunner startAwsNitroGatewayVsockTlsBridge(
            LinuxVsockTlsBridge bridge) {
        return arguments -> bridge.start();
    }
}
