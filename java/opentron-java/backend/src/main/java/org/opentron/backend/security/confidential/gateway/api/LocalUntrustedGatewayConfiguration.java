package org.opentron.backend.security.confidential.gateway.api;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentron.backend.security.confidential.gateway.AesGcmSecureContextCodec;
import org.opentron.backend.security.confidential.gateway.ConfidentialGatewayService;
import org.opentron.backend.security.confidential.gateway.ConfidentialOperationGovernor;
import org.opentron.backend.security.confidential.gateway.GatewayPiiDetectorConfiguration;
import org.opentron.backend.security.confidential.gateway.PiiDetector;
import org.opentron.backend.security.confidential.gateway.SecureContextKey;
import org.opentron.backend.security.confidential.gateway.SecureContextKeyProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@Profile("local-untrusted")
@ConditionalOnProperty(name = "confidential.gateway.enabled", havingValue = "true")
@Import({ConfidentialOperationGovernorConfiguration.class, GatewayPiiDetectorConfiguration.class})
public class LocalUntrustedGatewayConfiguration implements WebMvcConfigurer {
    private final ConfidentialGatewayApiKeyInterceptor apiKeyInterceptor;

    public LocalUntrustedGatewayConfiguration(
            @Value("${CONFIDENTIAL_GATEWAY_API_TOKEN:}") String apiToken,
            @Value("${CONFIDENTIAL_GATEWAY_TENANT_ID:local-development}") String tenantId,
            @Value("${server.address:}") String serverAddress) {
        requireLoopbackBinding(serverAddress);
        GatewayCredentialAuthenticator authenticator =
                new LocalStaticBearerCredentialAuthenticator(apiToken, tenantId);
        this.apiKeyInterceptor = new ConfidentialGatewayApiKeyInterceptor(authenticator);
    }

    private static void requireLoopbackBinding(String serverAddress) {
        if (serverAddress == null || serverAddress.isBlank()) {
            throw new IllegalStateException(
                    "The local-untrusted Gateway requires server.address to be explicitly bound to loopback.");
        }
        try {
            if (!InetAddress.getByName(serverAddress).isLoopbackAddress()) {
                throw new IllegalStateException(
                        "The local-untrusted Gateway may only bind to a loopback address.");
            }
        } catch (UnknownHostException e) {
            throw new IllegalStateException(
                    "The local-untrusted Gateway server.address is not a valid loopback address.");
        }
    }

    @Bean
    public GatewayQuotaService gatewayQuotaService(
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

    @Bean
    public SecureContextKeyProvider secureContextKeyProvider(
            @Value("${CONFIDENTIAL_GATEWAY_CONTEXT_KEY:}") String encodedKey,
            @Value("${CONFIDENTIAL_GATEWAY_CONTEXT_KEY_ID:local-dev}") String keyId) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "CONFIDENTIAL_GATEWAY_CONTEXT_KEY must be a Base64-encoded 256-bit key.");
        }
        if (keyBytes.length != 32) {
            Arrays.fill(keyBytes, (byte) 0);
            throw new IllegalStateException(
                    "CONFIDENTIAL_GATEWAY_CONTEXT_KEY must be a Base64-encoded 256-bit key.");
        }

        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        Arrays.fill(keyBytes, (byte) 0);
        SecureContextKey contextKey = new SecureContextKey(keyId, key);
        return new SecureContextKeyProvider() {
            @Override
            public SecureContextKey currentKey() {
                return contextKey;
            }

            @Override
            public Optional<SecureContextKey> findKey(String id) {
                return contextKey.id().equals(id) ? Optional.of(contextKey) : Optional.empty();
            }
        };
    }

    @Bean
    public AesGcmSecureContextCodec secureContextCodec(
            SecureContextKeyProvider keyProvider,
            ObjectMapper objectMapper) {
        return new AesGcmSecureContextCodec(
                keyProvider, objectMapper, new SecureRandom(), Clock.systemUTC());
    }

    @Bean
    public ConfidentialGatewayService confidentialGatewayService(
            AesGcmSecureContextCodec contextCodec,
            ConfidentialOperationGovernor operationGovernor,
            PiiDetector piiDetector,
            @Value("${CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES:5}") long ttlMinutes) {
        if (ttlMinutes <= 0 || ttlMinutes > 15) {
            throw new IllegalStateException(
                    "CONFIDENTIAL_GATEWAY_CONTEXT_TTL_MINUTES must be between 1 and 15.");
        }
        return new ConfidentialGatewayService(
                piiDetector,
                contextCodec,
                Duration.ofMinutes(ttlMinutes),
                new SecureRandom(),
                Clock.systemUTC(),
                operationGovernor);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor).addPathPatterns("/v1/confidential/**");
    }
}
