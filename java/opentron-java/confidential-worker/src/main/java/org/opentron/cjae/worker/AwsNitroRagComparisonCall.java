package org.opentron.cjae.worker;

import java.util.List;

public record AwsNitroRagComparisonCall(
        int recordCount,
        int warmupSamplesExcluded,
        int measuredSamples,
        String query,
        List<JavaSample> javaSamples,
        PythonDocument python,
        boolean javaPythonResultsMatch,
        boolean encryptedPromptRoundTripVerified) {

    public AwsNitroRagComparisonCall {
        javaSamples = List.copyOf(javaSamples);
    }

    public record JavaSample(
            int sample,
            long elapsedNanos,
            long processCpuNanos,
            long processPeakRssKiB,
            long nativeIndexBytes,
            String promptSha256,
            List<Integer> topRecordIds) {

        public JavaSample {
            topRecordIds = List.copyOf(topRecordIds);
        }
    }

    public record PythonDocument(
            String benchmark,
            int recordCount,
            int warmupSamplesExcluded,
            int measuredSamples,
            int serializationBatchSize,
            long totalSerializedBytes,
            List<PythonSample> samples) {

        public PythonDocument {
            samples = List.copyOf(samples);
        }
    }

    public record PythonSample(
            int sample,
            long elapsedNanos,
            long processCpuNanos,
            long processPeakRssKiB,
            long serializedBytes,
            String promptSha256,
            List<Integer> topRecordIds,
            List<Match> topMatches) {

        public PythonSample {
            topRecordIds = List.copyOf(topRecordIds);
            topMatches = List.copyOf(topMatches);
        }
    }

    public record Match(int recordId, int dotProduct, int recordNormSquared) {
    }
}
