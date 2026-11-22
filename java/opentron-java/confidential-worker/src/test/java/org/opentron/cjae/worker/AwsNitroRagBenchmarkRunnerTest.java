package org.opentron.cjae.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class AwsNitroRagBenchmarkRunnerTest {
    @Test
    void publishesFailureWhenStartupAttestationDidNotPass() throws Exception {
        AwsNitroRagBenchmarkOperation operation = mock(AwsNitroRagBenchmarkOperation.class);
        AwsNitroConfidentialBenchmarkReport report = new AwsNitroConfidentialBenchmarkReport();
        AwsNitroStartupStatus startupStatus = new AwsNitroStartupStatus(true);
        startupStatus.verificationFailed("KmsFailure");
        AwsNitroRagBenchmarkRunner runner = new AwsNitroRagBenchmarkRunner(
                operation,
                report,
                new ObjectMapper(),
                startupStatus,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        runner.run(new DefaultApplicationArguments(new String[0]));

        assertThat(report.response().statusCode()).isEqualTo(503);
        assertThat(new String(report.response().body(), StandardCharsets.UTF_8))
                .contains("startup_verification_failed");
        verifyNoInteractions(operation);
    }
}
