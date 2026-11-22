package org.opentron.cjae.worker;

import java.security.SecureRandom;
import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentron.backend.security.confidential.gateway.AesGcmSecureContextCodec;
import org.opentron.backend.security.confidential.gateway.SecureContextKeyProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("aws-nitro & !gcp-cvm & !local-untrusted")
@Conditional(AwsNitroBenchmarkEnabledCondition.class)
public class AwsNitroConfidentialBenchmarkConfiguration {
    @Bean
    public AesGcmSecureContextCodec awsNitroBenchmarkSecureContextCodec(
            SecureContextKeyProvider keyProvider,
            ObjectMapper objectMapper) {
        return new AesGcmSecureContextCodec(
                keyProvider,
                objectMapper,
                new SecureRandom(),
                Clock.systemUTC());
    }

    @Bean(destroyMethod = "clear")
    public AwsNitroConfidentialBenchmarkReport awsNitroConfidentialBenchmarkReport() {
        return new AwsNitroConfidentialBenchmarkReport();
    }
}
