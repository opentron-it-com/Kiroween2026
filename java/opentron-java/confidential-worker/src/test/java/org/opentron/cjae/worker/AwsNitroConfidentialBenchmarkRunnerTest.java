package org.opentron.cjae.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class AwsNitroConfidentialBenchmarkRunnerTest {
    @Test
    void publishesOnlyMeasuredResultsAndRequiresSuccessfulRoundTrips() throws Exception {
        AwsNitroConfidentialBenchmarkOperation operation =
                mock(AwsNitroConfidentialBenchmarkOperation.class);
        when(operation.encryptAndVerify(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new AwsNitroConfidentialBenchmarkCall(
                        64, 140, 100, 80, true));
        AwsNitroConfidentialBenchmarkReport report = new AwsNitroConfidentialBenchmarkReport();
        AwsNitroConfidentialBenchmarkRunner runner =
                new AwsNitroConfidentialBenchmarkRunner(
                        operation,
                        report,
                        new ObjectMapper(),
                        1,
                        3,
                        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        runner.run(new DefaultApplicationArguments(new String[0]));
        JsonNode document = new ObjectMapper().readTree(report.response().body());

        assertThat(report.response().statusCode()).isEqualTo(200);
        assertThat(document.at("/summary/sampleCount").asInt()).isEqualTo(3);
        assertThat(document.at("/summary/allEncryptionRoundTripsVerified").asBoolean()).isTrue();
        assertThat(document.at("/methodology/gatewayDisabled").asBoolean()).isTrue();
        assertThat(document.at("/samples/0/roundTripVerified").asBoolean()).isTrue();
        assertThat(document.toString()).doesNotContain("protected-value");
    }

    @Test
    void reportsStartupFailureWithoutRunningBenchmarkCalls() throws Exception {
        AwsNitroConfidentialBenchmarkOperation operation =
                mock(AwsNitroConfidentialBenchmarkOperation.class);
        AwsNitroConfidentialBenchmarkReport report = new AwsNitroConfidentialBenchmarkReport();
        AwsNitroStartupStatus startupStatus = new AwsNitroStartupStatus(true);
        startupStatus.verificationFailed("IllegalStateException");
        AwsNitroConfidentialBenchmarkRunner runner =
                new AwsNitroConfidentialBenchmarkRunner(
                        operation,
                        report,
                        new ObjectMapper(),
                        1,
                        3,
                        Clock.systemUTC(),
                        startupStatus);

        runner.run(new DefaultApplicationArguments(new String[0]));

        assertThat(report.response().statusCode()).isEqualTo(503);
        assertThat(new String(report.response().body(), StandardCharsets.UTF_8))
                .isEqualTo(
                        "{\"status\":\"benchmark_failed\","
                                + "\"failureCode\":\"startup_verification_failed\"}");
        verifyNoInteractions(operation);
    }
}
