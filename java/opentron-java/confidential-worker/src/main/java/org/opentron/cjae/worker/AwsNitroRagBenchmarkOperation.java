package org.opentron.cjae.worker;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import java.lang.management.ManagementFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.OperatingSystemMXBean;
import org.opentron.backend.security.confidential.ConfidentialAI;
import org.opentron.backend.security.confidential.ConfidentialSegment;
import org.opentron.backend.security.confidential.gateway.AesGcmSecureContextCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("aws-nitro & !gcp-cvm & !local-untrusted")
@Conditional(AwsNitroHealthVsockEnabledCondition.class)
@ConditionalOnProperty(
        name = "confidential.aws.nitro.rag-benchmark.enabled",
        havingValue = "true")
public class AwsNitroRagBenchmarkOperation {
    private static final String TENANT_ID = "thesis-rag-benchmark";
    private static final String JAVA_PROMPT = "<CJAE_JAVA_RAG_PROMPT>";
    private static final String PYTHON_PROMPT = "<CJAE_PYTHON_RAG_PROMPT>";
    private static final Duration CONTEXT_LIFETIME = Duration.ofMinutes(5);
    private static final long PYTHON_TIMEOUT_MINUTES = 20;
    private static final OperatingSystemMXBean OPERATING_SYSTEM_BEAN =
            requireOperatingSystemBean();

    private final AwsNitroRagPipeline pipeline;
    private final AesGcmSecureContextCodec contextCodec;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String pythonExecutable;
    private final Path pythonScript;

    public AwsNitroRagBenchmarkOperation(
            AesGcmSecureContextCodec contextCodec,
            ObjectMapper objectMapper,
            @Value("${confidential.aws.nitro.rag-benchmark.python-executable:python3}")
                    String pythonExecutable,
            @Value("${confidential.aws.nitro.rag-benchmark.python-script:"
                    + "/opt/cjae/bin/python_serialized_proxy.py}") String pythonScript) {
        this(
                new AwsNitroRagPipeline(),
                contextCodec,
                objectMapper,
                Clock.systemUTC(),
                pythonExecutable,
                Path.of(pythonScript));
    }

    AwsNitroRagBenchmarkOperation(
            AwsNitroRagPipeline pipeline,
            AesGcmSecureContextCodec contextCodec,
            ObjectMapper objectMapper,
            Clock clock,
            String pythonExecutable,
            Path pythonScript) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.contextCodec = Objects.requireNonNull(contextCodec, "contextCodec");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pythonExecutable = Objects.requireNonNull(pythonExecutable, "pythonExecutable");
        this.pythonScript = Objects.requireNonNull(pythonScript, "pythonScript");
    }

    @ConfidentialAI
    public AwsNitroRagComparisonCall compare(
            int recordCount,
            int warmupSamples,
            int measuredSamples,
            int batchSize,
            String query,
            int topK) throws IOException, InterruptedException {
        validateRequest(recordCount, warmupSamples, measuredSamples, batchSize, query, topK);
        List<JavaRun> javaResults =
                new ArrayList<>(warmupSamples + measuredSamples);
        for (int index = 0; index < warmupSamples + measuredSamples; index++) {
            javaResults.add(runJavaSample(recordCount, query, topK));
        }

        AwsNitroRagComparisonCall.PythonDocument python =
                runPythonProxy(recordCount, warmupSamples, measuredSamples, batchSize, query, topK);
        if (python.recordCount() != recordCount
                || python.warmupSamplesExcluded() != warmupSamples
                || python.measuredSamples() != measuredSamples
                || python.samples().size() != measuredSamples) {
            throw new IllegalStateException("Python RAG proxy returned incomplete measurements.");
        }

        List<AwsNitroRagComparisonCall.JavaSample> measuredJava = new ArrayList<>(measuredSamples);
        List<String> protectedPrompts = new ArrayList<>(measuredSamples * 2);
        for (int index = 0; index < measuredSamples; index++) {
            JavaRun javaRun = javaResults.get(warmupSamples + index);
            AwsNitroRagPipeline.Result javaResult = javaRun.result();
            AwsNitroRagComparisonCall.PythonSample pythonResult = python.samples().get(index);
            if (!javaResult.topRecordIds().equals(pythonResult.topRecordIds())
                    || !javaResult.promptSha256().equals(pythonResult.promptSha256())) {
                throw new IllegalStateException(
                        "Java and Python RAG results differ for measured sample " + (index + 1) + ".");
            }
            measuredJava.add(toJavaSample(index + 1, javaRun));
            protectedPrompts.add(javaResult.syntheticPrompt());
            protectedPrompts.add(pythonPrompt(query, pythonResult.topMatches()));
        }
        boolean encryptedRoundTripVerified = encryptAndVerifyPrompts(protectedPrompts);

        return new AwsNitroRagComparisonCall(
                recordCount,
                warmupSamples,
                measuredSamples,
                query,
                measuredJava,
                python,
                true,
                encryptedRoundTripVerified);
    }

    private JavaRun runJavaSample(
            int recordCount, String query, int topK) {
        long cpuStarted = processCpuNanos();
        AwsNitroRagPipeline.Result result = pipeline.run(recordCount, query, topK);
        long cpuNanos = processCpuNanos() - cpuStarted;
        return new JavaRun(result, cpuNanos, processPeakRssKiB());
    }

    private static AwsNitroRagComparisonCall.JavaSample toJavaSample(
            int sample, JavaRun run) {
        AwsNitroRagPipeline.Result result = run.result();
        return new AwsNitroRagComparisonCall.JavaSample(
                sample,
                result.elapsedNanos(),
                run.processCpuNanos(),
                run.processPeakRssKiB(),
                result.nativeIndexBytes(),
                result.promptSha256(),
                result.topRecordIds());
    }

    private AwsNitroRagComparisonCall.PythonDocument runPythonProxy(
            int recordCount,
            int warmupSamples,
            int measuredSamples,
            int batchSize,
            String query,
            int topK) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                pythonExecutable,
                pythonScript.toString(),
                "--record-count", Integer.toString(recordCount),
                "--warmups", Integer.toString(warmupSamples),
                "--samples", Integer.toString(measuredSamples),
                "--batch-size", Integer.toString(batchSize),
                "--query", query,
                "--top-k", Integer.toString(topK))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        boolean completed;
        try {
            completed = process.waitFor(PYTHON_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw exception;
        }
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("Python RAG proxy exceeded its 20-minute time limit.");
        }
        byte[] output = process.getInputStream().readAllBytes();
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "Python RAG proxy failed with exit code " + process.exitValue() + ".");
        }
        return objectMapper.readValue(output, AwsNitroRagComparisonCall.PythonDocument.class);
    }

    private boolean encryptAndVerifyPrompts(List<String> prompts) {
        for (int index = 0; index < prompts.size(); index += 2) {
            try (ConfidentialSegment javaPrompt = ConfidentialSegment.fromUtf8(prompts.get(index));
                    ConfidentialSegment pythonPrompt =
                            ConfidentialSegment.fromUtf8(prompts.get(index + 1))) {
                String encrypted = contextCodec.sealNative(
                        TENANT_ID,
                        Map.of(JAVA_PROMPT, javaPrompt, PYTHON_PROMPT, pythonPrompt),
                        clock.instant().plus(CONTEXT_LIFETIME));
                try (AesGcmSecureContextCodec.SecureContextMappings opened =
                        contextCodec.openNative(TENANT_ID, encrypted)) {
                    ConfidentialSegment recoveredJava = opened.values().get(JAVA_PROMPT);
                    ConfidentialSegment recoveredPython = opened.values().get(PYTHON_PROMPT);
                    if (recoveredJava == null
                            || recoveredPython == null
                            || !prompts.get(index).equals(recoveredJava.toUtf8String())
                            || !prompts.get(index + 1).equals(recoveredPython.toUtf8String())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static String pythonPrompt(
            String query, List<AwsNitroRagComparisonCall.Match> matches) {
        StringBuilder result = new StringBuilder("query=").append(query).append('\n');
        for (AwsNitroRagComparisonCall.Match match : matches) {
            result.append(match.recordId())
                    .append(',')
                    .append(match.dotProduct())
                    .append(',')
                    .append(match.recordNormSquared())
                    .append('\n');
        }
        return result.toString();
    }

    private static void validateRequest(
            int recordCount,
            int warmupSamples,
            int measuredSamples,
            int batchSize,
            String query,
            int topK) {
        if (recordCount < 1 || recordCount > AwsNitroRagPipeline.MAX_RECORDS
                || warmupSamples < 1 || measuredSamples < 1 || measuredSamples > 10
                || batchSize < 1 || batchSize > 100_000
                || topK < 1 || topK > 100 || topK > recordCount
                || !AwsNitroRagPipeline.isSyntheticQuery(query)) {
            throw new IllegalArgumentException("AWS Nitro RAG benchmark parameters are invalid.");
        }
    }

    private static OperatingSystemMXBean requireOperatingSystemBean() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        if (!(bean instanceof OperatingSystemMXBean extended)) {
            throw new IllegalStateException("Process CPU time metrics are unavailable.");
        }
        return extended;
    }

    private static long processCpuNanos() {
        long value = OPERATING_SYSTEM_BEAN.getProcessCpuTime();
        if (value < 0) {
            throw new IllegalStateException("Process CPU time metrics are unavailable.");
        }
        return value;
    }

    private static long processPeakRssKiB() {
        try {
            for (String line : java.nio.file.Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (IOException | NumberFormatException exception) {
            throw new IllegalStateException("Process peak RSS metrics are unavailable.", exception);
        }
        throw new IllegalStateException("Process peak RSS metrics are unavailable.");
    }

    private record JavaRun(
            AwsNitroRagPipeline.Result result,
            long processCpuNanos,
            long processPeakRssKiB) {
    }
}
