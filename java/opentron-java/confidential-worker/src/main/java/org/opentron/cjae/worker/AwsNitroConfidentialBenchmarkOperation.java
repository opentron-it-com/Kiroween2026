package org.opentron.cjae.worker;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.opentron.backend.security.confidential.ConfidentialAI;
import org.opentron.backend.security.confidential.ConfidentialSegment;
import org.opentron.backend.security.confidential.gateway.AesGcmSecureContextCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("aws-nitro & !gcp-cvm & !local-untrusted")
@Conditional(AwsNitroHealthVsockEnabledCondition.class)
@ConditionalOnProperty(
        name = "confidential.aws.nitro.benchmark.enabled",
        havingValue = "true")
public class AwsNitroConfidentialBenchmarkOperation {
    private static final String TENANT_ID = "thesis-benchmark";
    private static final String TOKEN = "<CJAE_BENCHMARK_SECRET>";
    private static final Duration CONTEXT_LIFETIME = Duration.ofMinutes(5);

    private final AesGcmSecureContextCodec contextCodec;
    private final Clock clock;

    public AwsNitroConfidentialBenchmarkOperation(AesGcmSecureContextCodec contextCodec) {
        this(contextCodec, Clock.systemUTC());
    }

    AwsNitroConfidentialBenchmarkOperation(
            AesGcmSecureContextCodec contextCodec, Clock clock) {
        this.contextCodec = Objects.requireNonNull(contextCodec, "contextCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @ConfidentialAI
    public AwsNitroConfidentialBenchmarkCall encryptAndVerify(String syntheticPayload) {
        Objects.requireNonNull(syntheticPayload, "syntheticPayload");
        byte[] encodedPayload = syntheticPayload.getBytes(StandardCharsets.UTF_8);
        try (ConfidentialSegment secret = ConfidentialSegment.fromUtf8(syntheticPayload)) {
            Map<String, ConfidentialSegment> mappings = Map.of(TOKEN, secret);
            long encryptionStarted = System.nanoTime();
            String encryptedContext = contextCodec.sealNative(
                    TENANT_ID, mappings, clock.instant().plus(CONTEXT_LIFETIME));
            long encryptionNanos = System.nanoTime() - encryptionStarted;

            long decryptionStarted = System.nanoTime();
            String restored;
            try (AesGcmSecureContextCodec.SecureContextMappings opened =
                    contextCodec.openNative(TENANT_ID, encryptedContext)) {
                ConfidentialSegment recovered = opened.values().get(TOKEN);
                if (recovered == null) {
                    throw new IllegalStateException(
                            "Encrypted benchmark context did not contain its protected value.");
                }
                restored = recovered.toUtf8String();
            }
            long decryptionNanos = System.nanoTime() - decryptionStarted;
            if (!syntheticPayload.equals(restored)) {
                throw new IllegalStateException(
                        "Encrypted benchmark context failed its plaintext round trip.");
            }
            if (!encryptedContext.startsWith("v3.")) {
                throw new IllegalStateException(
                        "Encrypted benchmark context used an unexpected format.");
            }
            return new AwsNitroConfidentialBenchmarkCall(
                    encodedPayload.length,
                    encryptedContext.getBytes(StandardCharsets.UTF_8).length,
                    encryptionNanos,
                    decryptionNanos,
                    true);
        } finally {
            java.util.Arrays.fill(encodedPayload, (byte) 0);
        }
    }
}
