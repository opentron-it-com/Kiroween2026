package org.opentron.cjae.worker;

import java.io.IOException;
import java.time.Clock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        name = "confidential.aws.nitro.rag-benchmark.enabled",
        havingValue = "true")
@Order(50)
public class AwsNitroRagBenchmarkRunner implements ApplicationRunner {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(AwsNitroRagBenchmarkRunner.class);
    static final int RECORD_COUNT = 1_000_000;
    static final int WARMUP_SAMPLES = 1;
    static final int MEASURED_SAMPLES = 3;
    static final int SERIALIZATION_BATCH_SIZE = 10_000;
    static final int TOP_K = 10;
    static final String QUERY = "segment-7 region-11 product-42";

    private final AwsNitroRagBenchmarkOperation operation;
    private final AwsNitroConfidentialBenchmarkReport report;
    private final ObjectMapper objectMapper;
    private final AwsNitroStartupStatus startupStatus;
    private final Clock clock;

    @Autowired
    public AwsNitroRagBenchmarkRunner(
            AwsNitroRagBenchmarkOperation operation,
            AwsNitroConfidentialBenchmarkReport report,
            ObjectMapper objectMapper,
            AwsNitroStartupStatus startupStatus) {
        this(operation, report, objectMapper, startupStatus, Clock.systemUTC());
    }

    AwsNitroRagBenchmarkRunner(
            AwsNitroRagBenchmarkOperation operation,
            AwsNitroConfidentialBenchmarkReport report,
            ObjectMapper objectMapper,
            AwsNitroStartupStatus startupStatus,
            Clock clock) {
        this.operation = operation;
        this.report = report;
        this.objectMapper = objectMapper;
        this.startupStatus = startupStatus;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments arguments) throws IOException, InterruptedException {
        if (!startupStatus.snapshot().ready()) {
            report.fail("startup_verification_failed");
            return;
        }
        try {
            AwsNitroRagComparisonCall result = operation.compare(
                    RECORD_COUNT,
                    WARMUP_SAMPLES,
                    MEASURED_SAMPLES,
                    SERIALIZATION_BATCH_SIZE,
                    QUERY,
                    TOP_K);
            if (!result.javaPythonResultsMatch() || !result.encryptedPromptRoundTripVerified()) {
                throw new IllegalStateException(
                        "AWS Nitro RAG comparison failed result or encryption verification.");
            }
            report.publish(objectMapper.writeValueAsBytes(new BenchmarkDocument(
                    "aws-nitro-confidential-rag-java-vs-python-serialized-proxy",
                    clock.instant().toString(),
                    new Methodology(
                            result.recordCount(),
                            result.warmupSamplesExcluded(),
                            result.measuredSamples(),
                            result.python().serializationBatchSize(),
                            true,
                            true,
                            "verified".equals(startupStatus.snapshot().status()),
                            true,
                            "Both pipelines ran sequentially inside the same Nitro Enclave. "
                                    + "The Java path tokenizes synthetic rows, builds a 16-dimensional "
                                    + "hash embedding index in zeroizing Panama off-heap memory, searches "
                                    + "top-k cosine matches, and packages a prompt. The Python proxy "
                                    + "runs the same deterministic workload but pickles and unpickles "
                                    + "each input batch. This is a local Python serialization proxy, "
                                    + "not Apache Spark or Opaque. Per-sample pipeline latency excludes "
                                    + "the single enclosing @ConfidentialAI runtime verification; "
                                    + "the protected prompts are AES-GCM sealed and reopened. Java RSS "
                                    + "includes the worker JVM; Python RSS covers its subprocess."),
                    result.query(),
                    result.javaSamples(),
                    result.python().samples(),
                    result.python().totalSerializedBytes(),
                    result.javaPythonResultsMatch(),
                    result.encryptedPromptRoundTripVerified())));
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (!startupStatus.diagnosticsEnabled()) {
                throw exception;
            }
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            report.fail("benchmark_execution_failed");
            LOGGER.warn(
                    "AWS Nitro RAG benchmark failed; sanitized failureType={}",
                    exception.getClass().getSimpleName());
        }
    }

    public record BenchmarkDocument(
            String benchmark,
            String generatedAtUtc,
            Methodology methodology,
            String syntheticQuery,
            java.util.List<AwsNitroRagComparisonCall.JavaSample> javaSamples,
            java.util.List<AwsNitroRagComparisonCall.PythonSample> pythonSamples,
            long totalPythonSerializedBytes,
            boolean matchingRetrievalResults,
            boolean everyPromptAesGcmRoundTripVerified) {
    }

    public record Methodology(
            int syntheticRecordsPerSample,
            int warmupSamplesExcluded,
            int measuredSamples,
            int pythonSerializationBatchSize,
            boolean sameNitroEnclave,
            boolean gatewayDisabled,
            boolean startupAttestationPassed,
            boolean everyAnnotatedCallPassedAesGcmRoundTrip,
            String measuredPath) {
    }
}
