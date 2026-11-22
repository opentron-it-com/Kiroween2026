package org.opentron.cjae.worker;

import java.util.Arrays;

import org.opentron.backend.security.confidential.gateway.aws.LinuxVsockHealthBridge;

public final class AwsNitroConfidentialBenchmarkReport {
    private volatile byte[] serializedReport;
    private volatile String failureCode;

    public synchronized void publish(byte[] report) {
        if (serializedReport != null || failureCode != null) {
            throw new IllegalStateException("The AWS Nitro benchmark report is already published.");
        }
        serializedReport = report.clone();
    }

    public synchronized void fail(String code) {
        if (serializedReport != null || failureCode != null
                || !("startup_verification_failed".equals(code)
                        || "benchmark_execution_failed".equals(code))) {
            throw new IllegalStateException("The AWS Nitro benchmark failure is invalid.");
        }
        failureCode = code;
    }

    public LinuxVsockHealthBridge.HealthResponse response() {
        byte[] report = serializedReport;
        String failed = failureCode;
        if (failed != null) {
            return new LinuxVsockHealthBridge.HealthResponse(
                    503,
                    ("{\"status\":\"benchmark_failed\",\"failureCode\":\""
                            + failed + "\"}").getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8));
        }
        if (report == null) {
            return new LinuxVsockHealthBridge.HealthResponse(
                    503, "{\"status\":\"benchmark_not_ready\"}".getBytes(
                            java.nio.charset.StandardCharsets.UTF_8));
        }
        return new LinuxVsockHealthBridge.HealthResponse(200, report.clone());
    }

    public synchronized void clear() {
        if (serializedReport != null) {
            Arrays.fill(serializedReport, (byte) 0);
            serializedReport = null;
        }
        failureCode = null;
    }
}
