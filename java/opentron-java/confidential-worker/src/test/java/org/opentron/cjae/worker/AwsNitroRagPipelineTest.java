package org.opentron.cjae.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AwsNitroRagPipelineTest {
    private static final String QUERY = "segment-7 region-11 product-42";

    @Test
    void buildsOffHeapIndexAndMatchesPythonProxyGoldenRetrieval() {
        AwsNitroRagPipeline.Result result =
                new AwsNitroRagPipeline().run(1_000, QUERY, 10);

        assertThat(result.recordCount()).isEqualTo(1_000);
        assertThat(result.nativeIndexBytes())
                .isEqualTo(1_000L * AwsNitroRagPipeline.EMBEDDING_DIMENSIONS * Integer.BYTES);
        assertThat(result.elapsedNanos()).isPositive();
        assertThat(result.topRecordIds())
                .containsExactly(420, 472, 203, 433, 906, 929, 3, 29, 96, 98);
        assertThat(result.promptSha256())
                .isEqualTo("a5486132aa29ab9211317fc3a33378eea29e072ed12faf4e6553320522acdc00");
    }

    @Test
    void rejectsInvalidBenchmarkParametersBeforeAllocatingMemory() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> new AwsNitroRagPipeline().run(
                                AwsNitroRagPipeline.MAX_RECORDS + 1, QUERY, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> new AwsNitroRagPipeline().run(10, "real customer data", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
