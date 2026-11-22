package org.opentron.backend.security.confidential.gateway.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.opentron.backend.security.confidential.ConfidentialRuntimeVerifier;
import org.opentron.backend.security.confidential.gateway.SecureContextKeyProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * GCP adapter configuration for the {@code gcp-cvm} profile.
 *
 * <p>Required properties: {@code confidential.gcp.attestation.audience},
 * {@code confidential.gcp.attestation.hardware-model} ({@code AMD_SEV} or
 * {@code INTEL_TDX}), {@code confidential.gcp.attestation.allowed-projects},
 * {@code confidential.gcp.attestation.allowed-zones},
 * {@code confidential.gcp.attestation.allowed-instances},
 * {@code confidential.gcp.wif.audience} (the canonical provider resource name),
 * {@code confidential.gcp.kms.key-resource},
 * {@code confidential.gcp.kms.context-key-id}, and
 * {@code confidential.gcp.kms.ciphertext-base64}. The ciphertext must be a
 * previously Cloud-KMS-encrypted 256-bit context DEK. This configuration uses
 * the packaged Google {@code gotpm} vTPM token source. The API key
 * release path remains disabled unless the GCP gateway is explicitly enabled
 * and its WIF/KMS configuration is supplied.
 */
@Configuration(proxyBeanMethods = false)
@Profile("gcp-cvm")
public class GcpConfidentialGatewayConfiguration {
    @Bean
    public GcpAttestationTokenSource gcpAttestationTokenSource(
            @Value("${confidential.gcp.attestation.gotpm-path}") Path gotpmPath,
            @Value("${confidential.gcp.attestation.event-log-path}") Path eventLogPath,
            @Value("${confidential.gcp.attestation.hardware-model}") String hardwareModel) {
        String teeTechnology = switch (GcpAttestationPolicy.HardwareModel.valueOf(hardwareModel)) {
            case AMD_SEV -> "";
            case INTEL_TDX -> "tdx";
        };
        return new GoTpmGcpAttestationTokenSource(
                gotpmPath, teeTechnology, eventLogPath, Duration.ofSeconds(45));
    }

    @Bean
    public GcpHttpTransport gcpHttpTransport() {
        return new JavaGcpHttpTransport(Duration.ofSeconds(3));
    }

    @Bean
    public GcpAttestationPolicy gcpAttestationPolicy(
            @Value("${confidential.gcp.attestation.audience}") String audience,
            @Value("${confidential.gcp.attestation.hardware-model}") String hardwareModel,
            @Value("${confidential.gcp.attestation.allowed-projects}") String projects,
            @Value("${confidential.gcp.attestation.allowed-zones}") String zones,
            @Value("${confidential.gcp.attestation.allowed-instances}") String instances,
            @Value("${confidential.gcp.attestation.expected-image-digest:}")
                    String expectedImageDigest,
            @Value("${confidential.gateway.enabled:false}") boolean gatewayEnabled) {
        if (gatewayEnabled && (expectedImageDigest == null || expectedImageDigest.isBlank())) {
            throw new IllegalStateException(
                    "The GCP confidential gateway requires a pinned Confidential Space workload image digest.");
        }
        return new GcpAttestationPolicy(
                audience,
                GcpAttestationPolicy.HardwareModel.valueOf(hardwareModel),
                csv(projects),
                csv(zones),
                csv(instances),
                expectedImageDigest);
    }

    @Bean
    public GcpAttestationJwtDecoder gcpAttestationJwtDecoder(
            GcpHttpTransport transport,
            ObjectMapper objectMapper) {
        return new GoogleGcaJwtDecoder(transport, objectMapper);
    }

    @Bean
    public GcpGcaAttestationVerifier gcpGcaAttestationVerifier(
            GcpAttestationTokenSource tokenSource,
            GcpAttestationJwtDecoder decoder,
            GcpAttestationPolicy policy) {
        return new GcpGcaAttestationVerifier(
                tokenSource, decoder, policy, Clock.systemUTC(), new SecureRandom());
    }

    @Bean
    public ConfidentialRuntimeVerifier gcpConfidentialRuntimeVerifier(
            GcpGcaAttestationVerifier attestationVerifier) {
        return new GcpConfidentialRuntimeVerifier(attestationVerifier);
    }

    @Bean
    @ConditionalOnProperty(name = "confidential.gateway.enabled", havingValue = "true")
    public SecureContextKeyProvider gcpSecureContextKeyProvider(
            GcpGcaAttestationVerifier attestationVerifier,
            GcpHttpTransport transport,
            ObjectMapper objectMapper,
            @Value("${confidential.gcp.wif.audience}") String wifAudience,
            @Value("${confidential.gcp.kms.key-resource}") String keyResource,
            @Value("${confidential.gcp.kms.context-key-id}") String contextKeyId,
            @Value("${confidential.gcp.kms.ciphertext-base64}") String ciphertext) {
        return new GcpWifKmsSecureContextKeyProvider(
                attestationVerifier,
                transport,
                objectMapper,
                wifAudience,
                keyResource,
                contextKeyId,
                ciphertext);
    }

    private static Set<String> csv(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
