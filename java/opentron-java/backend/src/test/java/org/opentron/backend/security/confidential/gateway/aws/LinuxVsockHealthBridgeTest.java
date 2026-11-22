package org.opentron.backend.security.confidential.gateway.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LinuxVsockHealthBridgeTest {
    @Test
    void permitsOnlyHealthGetRequestLines() {
        assertThat(LinuxVsockHealthBridge.isHealthRequestLine("GET /health HTTP/1.0")).isTrue();
        assertThat(LinuxVsockHealthBridge.isHealthRequestLine("GET /health HTTP/1.1")).isTrue();

        assertThat(LinuxVsockHealthBridge.isHealthRequestLine("POST /health HTTP/1.1")).isFalse();
        assertThat(LinuxVsockHealthBridge.isHealthRequestLine("GET / HTTP/1.1")).isFalse();
        assertThat(LinuxVsockHealthBridge.isHealthRequestLine(
                "GET /health/../gateway HTTP/1.1")).isFalse();
        assertThat(LinuxVsockHealthBridge.isHealthRequestLine("get /health HTTP/1.1")).isFalse();
    }

    @Test
    void permitsOnlyExactBenchmarkResultGetRequestLines() {
        assertThat(LinuxVsockHealthBridge.isBenchmarkResultsRequestLine(
                "GET /benchmark/results HTTP/1.0")).isTrue();
        assertThat(LinuxVsockHealthBridge.isBenchmarkResultsRequestLine(
                "GET /benchmark/results HTTP/1.1")).isTrue();
        assertThat(LinuxVsockHealthBridge.isBenchmarkResultsRequestLine(
                "POST /benchmark/results HTTP/1.1")).isFalse();
        assertThat(LinuxVsockHealthBridge.isBenchmarkResultsRequestLine(
                "GET /benchmark/results/../gateway HTTP/1.1")).isFalse();
    }

    @Test
    void rejectsInvalidAddressValues() {
        LinuxVsockHealthBridge.HealthResponseProvider health =
                () -> new LinuxVsockHealthBridge.HealthResponse(200, new byte[0]);
        assertThatThrownBy(() -> new LinuxVsockHealthBridge(3, 0, health))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LinuxVsockHealthBridge(-1, 8002, health))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsHealthOnlyHttpResponse() {
        byte[] response = LinuxVsockHealthBridge.httpResponse(
                200, "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(response, StandardCharsets.UTF_8))
                .contains("HTTP/1.1 200 OK\r\n")
                .contains("Content-Type: application/json; charset=utf-8\r\n")
                .contains("Content-Length: 15\r\n")
                .endsWith("{\"status\":\"ok\"}");
    }
}
