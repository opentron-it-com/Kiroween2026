package org.opentron.backend.security.confidential.gateway.aws;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentron.backend.security.confidential.ConfidentialRuntimeVerifier;
import org.opentron.backend.security.confidential.gateway.SecureContextKeyProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires enclave-side AWS Nitro key release. The default NSM source uses Panama
 * FFM directly against /dev/nsm; deployments must provide a trusted parent KMS proxy.
 * The separately guarded OIDC gateway module owns any HTTP request surface.
 */
@Configuration(proxyBeanMethods = false)
@Profile("aws-nitro & !gcp-cvm & !local-untrusted")
public class AwsNitroConfidentialGatewayConfiguration {
    @Bean
    @ConditionalOnMissingBean(AwsNitroEnclaveAttestationSource.class)
    public AwsNitroEnclaveAttestationSource awsNitroEnclaveAttestationSource() {
        return new PanamaNitroEnclaveAttestationSource();
    }

    @Bean
    @ConditionalOnMissingBean(ParentVsockKmsTransport.class)
    public ParentVsockKmsTransport parentVsockKmsTransport(
            @Value("${confidential.aws.nitro.vsock.parent-cid:3}") int parentCid,
            @Value("${confidential.aws.nitro.vsock.proxy-port:8000}") int proxyPort) {
        return new LinuxVsockParentKmsTransport(
                parentCid, proxyPort, new ObjectMapper());
    }

    @Bean
    public AwsNitroEnclaveKeyReleaseService awsNitroEnclaveKeyReleaseService(
            AwsNitroEnclaveAttestationSource attestationSource,
            ParentVsockKmsTransport kmsTransport,
            @Value("${confidential.aws.nitro.kms.key-id}") String kmsKeyId,
            @Value("${confidential.aws.nitro.kms.ciphertext-blob-base64}")
                    String encodedCiphertext,
            @Value("${confidential.aws.nitro.kms.context-key-id}") String contextKeyId) {
        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(encodedCiphertext);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "confidential.aws.nitro.kms.ciphertext-blob-base64 must be Base64.", e);
        }
        try {
            return new AwsNitroEnclaveKeyReleaseService(
                    attestationSource,
                    kmsTransport,
                    new SecureRandom(),
                    kmsKeyId,
                    ciphertext,
                    contextKeyId);
        } finally {
            java.util.Arrays.fill(ciphertext, (byte) 0);
        }
    }

    @Bean
    public SecureContextKeyProvider awsNitroSecureContextKeyProvider(
            AwsNitroEnclaveKeyReleaseService keyReleaseService,
            @Value("${confidential.aws.nitro.kms.context-key-id}") String contextKeyId) {
        return new AwsNitroAttestedSecureContextKeyProvider(keyReleaseService, contextKeyId);
    }

    @Bean
    public ConfidentialRuntimeVerifier awsNitroConfidentialRuntimeVerifier(
            AwsNitroEnclaveKeyReleaseService keyReleaseService) {
        return new AwsNitroConfidentialRuntimeVerifier(keyReleaseService);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "confidential.aws.nitro.kms",
            name = "policy-export-enabled",
            havingValue = "true")
    public AwsNitroKmsAttestationPolicy awsNitroKmsAttestationPolicy(
            @Value("${confidential.aws.nitro.kms.image-sha384}") String imageSha384,
            @Value("${confidential.aws.nitro.kms.required-pcrs}") String rawPcrs) {
        return new AwsNitroKmsAttestationPolicy(imageSha384, parsePcrs(rawPcrs));
    }

    private static Map<Integer, String> parsePcrs(String rawPcrs) {
        if (rawPcrs == null || rawPcrs.isBlank()) {
            throw new IllegalArgumentException(
                    "At least one confidential.aws.nitro.kms.required-pcrs entry is required.");
        }
        var pcrs = new HashMap<Integer, String>();
        for (String item : rawPcrs.split(",")) {
            String[] pair = item.trim().split("=", -1);
            if (pair.length != 2) {
                throw new IllegalArgumentException(
                        "AWS Nitro PCRs must use comma-separated index=sha384hex entries.");
            }
            try {
                int index = Integer.parseInt(pair[0]);
                if (pcrs.putIfAbsent(index, pair[1]) != null) {
                    throw new IllegalArgumentException("AWS Nitro PCR index is duplicated.");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("AWS Nitro PCR index must be an integer.", e);
            }
        }
        return Map.copyOf(pcrs);
    }
}
