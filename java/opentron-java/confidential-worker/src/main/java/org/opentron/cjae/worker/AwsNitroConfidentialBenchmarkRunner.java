package org.opentron.cjae.worker;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile("aws-nitro & !gcp-cvm & !local-untrusted")
@Conditional(AwsNitroHealthVsockEnabledCondition.class)
@ConditionalOnProperty(
        name = "confidential.aws.nitro.benchmark.enabled",
        havingValue = "true")
@Order(50)
public class AwsNitroConfidentialBenchmarkRunner implements ApplicationRunner {
    private final AwsNitroConfidentialBenchmarkOperation operation;
    private final AwsNitroConfidentialBenchmarkReport report;
    private final ObjectMapper objectMapper;
    private final int warmupRequests;
    private final int sampleCount;
    private final Clock clock;
    private final AwsNitroStartupStatus startupStatus;

    @Autowired
    public AwsNitroConfidentialBenchmarkRunner(
            AwsNitroConfidentialBenchmarkOperation operation,
            AwsNitroConfidentialBenchmarkReport report,
            ObjectMapper objectMapper,
            @Value("${confidential.aws.nitro.benchmark.warmup:10}") int warmupRequests,
            @Value("${confidential.aws.nitro.benchmark.samples:100}") int sampleCount,
            AwsNitroStartupStatus startupStatus) {
        this(
                operation,
                report,
                objectMapper,
                warmupRequests,
                sampleCount,
                Clock.systemUTC(),
                startupStatus);
    }

    AwsNitroConfidentialBenchmarkRunner(
            AwsNitroConfidentialBenchmarkOperation operation,
            AwsNitroConfidentialBenchmarkReport report,
            ObjectMapper objectMapper,
            int warmupRequests,
            int sampleCount,
            Clock clock) {
        this(
                operation,
                report,
                objectMapper,
                warmupRequests,
                sampleCount,
                clock,
                new AwsNitroStartupStatus(false));
    }

    AwsNitroConfidentialBenchmarkRunner(
            AwsNitroConfidentialBenchmarkOperation operation,
            AwsNitroConfidentialBenchmarkReport report,
            ObjectMapper objectMapper,
            int warmupRequests,
            int sampleCount,
            Clock clock,
            AwsNitroStartupStatus startupStatus) {
        if (warmupRequests < 1 || sampleCount < 1 || sampleCount > 500) {
            throw new IllegalArgumentException(
                    "AWS Nitro benchmark requires 1-500 samples and at least one warmup.");
        }
        this.operation = operation;
        this.report = report;
        this.objectMapper = objectMapper;
        this.warmupRequests = warmupRequests;
        this.sampleCount = sampleCount;
        this.clock = clock;
        this.startupStatus = startupStatus;
    }

    @Override
    public void run(ApplicationArguments arguments) throws IOException {
        if (!startupStatus.snapshot().ready()) {
            report.fail("startup_verification_failed");
            return;
        }
        for (int index = 0; index < warmupRequests; index++) {
            AwsNitroConfidentialBenchmarkCall result = operation.encryptAndVerify(payload(index));
            requireSuccessfulRoundTrip(result);
        }

        List<Sample> samples = new ArrayList<>(sampleCount);
        for (int index = 0; index < sampleCount; index++) {
            String payload = payload(index + warmupRequests);
            String startedAt = clock.instant().toString();
            long callStarted = System.nanoTime();
            AwsNitroConfidentialBenchmarkCall result = operation.encryptAndVerify(payload);
            long callNanos = System.nanoTime() - callStarted;
            requireSuccessfulRoundTrip(result);
            samples.add(new Sample(
                    index + 1,
                    startedAt,
                    result.plaintextBytes(),
                    result.encryptedContextBytes(),
                    callNanos,
                    result.encryptionNanos(),
                    result.decryptionNanos(),
                    result.roundTripVerified()));
        }
        report.publish(objectMapper.writeValueAsBytes(new BenchmarkDocument(
                "aws-nitro-confidential-annotation-aes-gcm-roundtrip",
                clock.instant().toString(),
                new Methodology(
                        warmupRequests,
                        sampleCount,
                        true,
                        true,
                        "Caller-to-return latency includes @ConfidentialAI runtime verification, "
                                + "fresh NSM/KMS key release, and AES-GCM context seal/open."),
                summarize(samples),
                samples)));
    }

    private static String payload(int index) {
        return "Synthetic thesis benchmark payload "
                + index
                + " | protected-value=subject-"
                + String.format("%06d", index)
                + "@example.invalid";
    }

    private static void requireSuccessfulRoundTrip(AwsNitroConfidentialBenchmarkCall result) {
        if (!result.roundTripVerified()
                || result.plaintextBytes() <= 0
                || result.encryptedContextBytes() <= result.plaintextBytes()) {
            throw new IllegalStateException(
                    "AWS Nitro annotated AES-GCM benchmark round trip failed.");
        }
    }

    private static Summary summarize(List<Sample> samples) {
        List<Long> values = samples.stream().map(Sample::annotatedCallNanos).sorted().toList();
        double mean = values.stream().mapToLong(Long::longValue).average().orElseThrow();
        return new Summary(
                values.size(),
                values.getFirst(),
                mean,
                percentile(values, 50),
                percentile(values, 90),
                percentile(values, 95),
                percentile(values, 99),
                values.getLast(),
                values.stream().allMatch(value -> value > 0),
                samples.stream().allMatch(Sample::roundTripVerified));
    }

    private static long percentile(List<Long> sortedValues, int percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1);
        return sortedValues.get(index);
    }

    public record BenchmarkDocument(
            String benchmark,
            String generatedAtUtc,
            Methodology methodology,
            Summary summary,
            List<Sample> samples) {
    }

    public record Methodology(
            int warmupRequestsExcluded,
            int measuredRequests,
            boolean gatewayDisabled,
            boolean everyAnnotatedCallPassedAesGcmRoundTrip,
            String measuredPath) {
    }

    public record Summary(
            int sampleCount,
            long minAnnotatedCallNanos,
            double meanAnnotatedCallNanos,
            long p50AnnotatedCallNanos,
            long p90AnnotatedCallNanos,
            long p95AnnotatedCallNanos,
            long p99AnnotatedCallNanos,
            long maxAnnotatedCallNanos,
            boolean allCallsPositiveDuration,
            boolean allEncryptionRoundTripsVerified) {
    }

    public record Sample(
            int sample,
            String timestampUtc,
            int plaintextBytes,
            int encryptedContextBytes,
            long annotatedCallNanos,
            long aesGcmEncryptNanos,
            long aesGcmDecryptNanos,
            boolean roundTripVerified) {
    }
}
