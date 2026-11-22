package org.opentron.backend.security.confidential.gateway.gcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoTpmGcpAttestationTokenSourceTest {
    private static final String AUDIENCE = "https://cjae.example/attestation";
    private static final String NONCE = "0123456789abcdefghijklmnopqrstuv";
    private static final String TOKEN = "header.payload.signature";
    private static final Path GOTPM = Path.of(System.getProperty("java.io.tmpdir"), "cjae", "bin", "gotpm")
            .toAbsolutePath();
    private static final Path EVENT_LOG = Path.of(System.getProperty("java.io.tmpdir"), "cjae", "tpm", "event.log")
            .toAbsolutePath();

    @Test
    void invokesGoogleToolWithAudienceNonceAndHardwareTechnology() {
        GoTpmGcpAttestationTokenSource source = new GoTpmGcpAttestationTokenSource(
                GOTPM,
                "tdx",
                EVENT_LOG,
                Duration.ofSeconds(45),
                (command, timeout) -> {
                    assertEquals(List.of(
                            GOTPM.toString(),
                            "token",
                            "--audience",
                            AUDIENCE,
                            "--custom-nonce",
                            NONCE,
                            "--event-log",
                            EVENT_LOG.toString(),
                            "--tee-technology",
                            "tdx"), command);
                    assertEquals(Duration.ofSeconds(45), timeout);
                    return TOKEN;
                });

        assertEquals(TOKEN, source.fetch(AUDIENCE, NONCE));
    }

    @Test
    void usesVtpmEvidenceWithoutAnUnsupportedSevSnpQuote() {
        GoTpmGcpAttestationTokenSource source = new GoTpmGcpAttestationTokenSource(
                GOTPM,
                "",
                EVENT_LOG,
                Duration.ofSeconds(45),
                (command, timeout) -> {
                    assertEquals(List.of(
                            GOTPM.toString(),
                            "token",
                            "--audience",
                            AUDIENCE,
                            "--custom-nonce",
                            NONCE,
                            "--event-log",
                            EVENT_LOG.toString()), command);
                    return TOKEN;
                });

        assertEquals(TOKEN, source.fetch(AUDIENCE, NONCE));
    }

    @Test
    void rejectsInvalidAudienceAndNonceBeforeInvokingGoogleTool() {
        GoTpmGcpAttestationTokenSource source = new GoTpmGcpAttestationTokenSource(
                GOTPM,
                "",
                EVENT_LOG,
                Duration.ofSeconds(45),
                (command, timeout) -> {
                    throw new AssertionError("Invalid input must not invoke gotpm.");
                });

        assertThrows(GcpAttestationException.class, () -> source.fetch("bad audience", NONCE));
        assertThrows(GcpAttestationException.class, () -> source.fetch(AUDIENCE, "short"));
    }

    @Test
    void rejectsNonJwtOutputAndUnsupportedHardware() {
        GoTpmGcpAttestationTokenSource invalidOutput = new GoTpmGcpAttestationTokenSource(
                GOTPM,
                "tdx",
                EVENT_LOG,
                Duration.ofSeconds(45),
                (command, timeout) -> "not-a-jwt");

        assertThrows(GcpAttestationException.class, () -> invalidOutput.fetch(AUDIENCE, NONCE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GoTpmGcpAttestationTokenSource(
                        GOTPM,
                        "unknown",
                        EVENT_LOG,
                        Duration.ofSeconds(45)));
    }
}
