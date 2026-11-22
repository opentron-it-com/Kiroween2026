package org.opentron.cjae.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.ConfidentialAIAspect;
import org.opentron.backend.security.confidential.ConfidentialRuntimeVerifier;
import org.opentron.backend.security.confidential.gateway.AesGcmSecureContextCodec;
import org.opentron.backend.security.confidential.gateway.SecureContextKey;
import org.opentron.backend.security.confidential.gateway.SecureContextKeyProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

class AwsNitroConfidentialBenchmarkOperationTest {
    @Test
    void annotatedCallVerifiesRuntimeAndEncryptsThenRestoresThePayload() {
        try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            AwsNitroConfidentialBenchmarkOperation operation =
                    context.getBean(AwsNitroConfidentialBenchmarkOperation.class);

            AwsNitroConfidentialBenchmarkCall result =
                    operation.encryptAndVerify("thesis synthetic protected value");

            assertThat(result.roundTripVerified()).isTrue();
            assertThat(result.encryptedContextBytes()).isGreaterThan(result.plaintextBytes());
            assertThat(result.encryptionNanos()).isPositive();
            assertThat(result.decryptionNanos()).isPositive();
            verify(context.getBean(ConfidentialRuntimeVerifier.class)).verifyCurrentRuntime();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfiguration {
        @Bean
        ConfidentialRuntimeVerifier runtimeVerifier() {
            return mock(ConfidentialRuntimeVerifier.class);
        }

        @Bean
        ConfidentialAIAspect confidentialAIAspect(ConfidentialRuntimeVerifier verifier) {
            return new ConfidentialAIAspect(java.util.List.of(verifier));
        }

        @Bean
        AesGcmSecureContextCodec secureContextCodec() {
            byte[] keyBytes = new byte[32];
            java.util.Arrays.fill(keyBytes, (byte) 7);
            SecureContextKey key = new SecureContextKey(
                    "thesis-key", new SecretKeySpec(keyBytes, "AES"));
            SecureContextKeyProvider provider = new SecureContextKeyProvider() {
                @Override
                public SecureContextKey currentKey() {
                    return key;
                }

                @Override
                public Optional<SecureContextKey> findKey(String id) {
                    return "thesis-key".equals(id) ? Optional.of(key) : Optional.empty();
                }
            };
            return new AesGcmSecureContextCodec(
                    provider,
                    new ObjectMapper(),
                    new SecureRandom(),
                    Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        }

        @Bean
        AwsNitroConfidentialBenchmarkOperation benchmarkOperation(
                AesGcmSecureContextCodec codec) {
            return new AwsNitroConfidentialBenchmarkOperation(codec);
        }
    }
}
