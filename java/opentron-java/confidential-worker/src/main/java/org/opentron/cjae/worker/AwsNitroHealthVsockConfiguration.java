package org.opentron.cjae.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentron.backend.security.confidential.gateway.aws.LinuxVsockHealthBridge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.ObjectProvider;

@Configuration(proxyBeanMethods = false)
@Profile("aws-nitro & !gcp-cvm & !local-untrusted")
@Conditional(AwsNitroHealthVsockEnabledCondition.class)
public class AwsNitroHealthVsockConfiguration {
    @Bean(destroyMethod = "close")
    public LinuxVsockHealthBridge awsNitroHealthVsockBridge(
            @Value("${confidential.aws.nitro.vsock.parent-cid:3}") int parentCid,
            @Value("${confidential.aws.nitro.health-vsock.port:8002}") int vsockPort,
            AwsNitroStartupStatus startupStatus,
            ObjectProvider<AwsNitroConfidentialBenchmarkReport> benchmarkReport,
            ObjectMapper objectMapper) {
        LinuxVsockHealthBridge.HealthResponseProvider healthResponse = () -> {
            var health = ConfidentialWorkerApplication.healthPayload(startupStatus.snapshot());
            return new LinuxVsockHealthBridge.HealthResponse(
                    health.awsNitroStartup().ready() ? 200 : 503,
                    objectMapper.writeValueAsBytes(health));
        };
        AwsNitroConfidentialBenchmarkReport report = benchmarkReport.getIfAvailable();
        if (report == null) {
            return new LinuxVsockHealthBridge(parentCid, vsockPort, healthResponse);
        }
        return new LinuxVsockHealthBridge(
                parentCid, vsockPort, healthResponse, report::response);
    }

    @Bean
    @Order(-100)
    public ApplicationRunner startAwsNitroHealthVsockBridge(
            LinuxVsockHealthBridge bridge) {
        return arguments -> bridge.start();
    }
}
