package org.opentron.backend.security.confidential.gateway.aws;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentron.backend.security.confidential.gateway.AesGcmSecureContextCodec;
import org.opentron.backend.security.confidential.gateway.ConfidentialGatewayService;
import org.opentron.backend.security.confidential.gateway.ConfidentialOperationGovernor;
import org.opentron.backend.security.confidential.gateway.GatewayPiiDetectorConfiguration;
import org.opentron.backend.security.confidential.gateway.PiiDetector;
import org.opentron.backend.security.confidential.gateway.SecureContextKeyProvider;
import org.opentron.backend.security.confidential.gateway.SecureContextConsumptionStore;
import org.opentron.backend.security.confidential.gateway.api.ConfidentialGatewayApiKeyInterceptor;
import org.opentron.backend.security.confidential.gateway.api.ConfidentialOperationGovernorConfiguration;
import org.opentron.backend.security.confidential.gateway.api.GatewayCredentialAuthenticator;
import org.opentron.backend.security.confidential.gateway.api.GatewayQuotaService;
import org.opentron.backend.security.confidential.gateway.api.FixedWindowGatewayQuotaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Profile("aws-nitro & gateway-oidc & !gcp-cvm")
@ConditionalOnProperty(name = "confidential.gateway.enabled", havingValue = "true")
@Import({ConfidentialOperationGovernorConfiguration.class, GatewayPiiDetectorConfiguration.class})
public class AwsNitroOidcGatewayApiConfiguration implements WebMvcConfigurer {
    private final ConfidentialGatewayApiKeyInterceptor apiKeyInterceptor;

    public AwsNitroOidcGatewayApiConfiguration(
            GatewayCredentialAuthenticator credentialAuthenticator) {
        apiKeyInterceptor = new ConfidentialGatewayApiKeyInterceptor(credentialAuthenticator);
    }

    @Bean
    public AesGcmSecureContextCodec awsNitroSecureContextCodec(
            SecureContextKeyProvider awsNitroSecureContextKeyProvider,
            ObjectMapper objectMapper) {
        return new AesGcmSecureContextCodec(
                awsNitroSecureContextKeyProvider,
                objectMapper,
                new SecureRandom(),
                Clock.systemUTC());
    }

    @Bean
    public ConfidentialGatewayService awsNitroConfidentialGatewayService(
            AesGcmSecureContextCodec awsNitroSecureContextCodec,
            ConfidentialOperationGovernor operationGovernor,
            PiiDetector piiDetector,
            SecureContextConsumptionStore contextConsumptionStore,
            @Value("${CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES:5}") long ttlMinutes) {
        if (ttlMinutes <= 0 || ttlMinutes > 15) {
            throw new IllegalStateException(
                    "CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES must be between 1 and 15.");
        }
        return new ConfidentialGatewayService(
                piiDetector,
                awsNitroSecureContextCodec,
                Duration.ofMinutes(ttlMinutes),
                new SecureRandom(),
                Clock.systemUTC(),
                operationGovernor,
                contextConsumptionStore);
    }

    @Bean
    public GatewayQuotaService awsNitroGatewayQuotaService(
            @Value("${CONFIDENTIAL_GATEWAY_MAX_REQUESTS_PER_MINUTE:60}")
                    int maximumRequests,
            @Value("${CONFIDENTIAL_GATEWAY_MAX_CHARACTERS_PER_MINUTE:5000000}")
                    long maximumCharacters,
            @Value("${CONFIDENTIAL_GATEWAY_MAX_TENANTS:10000}")
                    int maximumTenants) {
        return new FixedWindowGatewayQuotaService(
                maximumRequests,
                maximumCharacters,
                maximumTenants,
                Duration.ofMinutes(1),
                Clock.systemUTC());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor).addPathPatterns("/v1/confidential/**");
    }
}
