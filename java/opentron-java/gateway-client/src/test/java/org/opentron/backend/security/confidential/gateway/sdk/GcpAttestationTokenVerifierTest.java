package org.opentron.backend.security.confidential.gateway.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GcpAttestationTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final String CHALLENGE =
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final String IMAGE_DIGEST = "sha256:" + "a".repeat(64);
    private static final String SUBJECT =
            "https://www.googleapis.com/compute/v1/projects/cjae-prod/"
                    + "zones/us-central1-a/instances/cjae-gateway";

    private KeyPair keyPair;
    private ObjectMapper objectMapper;
    private GcpAttestationTokenVerifier verifier;
    private GcpConfidentialSpacePolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        objectMapper = new ObjectMapper();
        verifier = new GcpAttestationTokenVerifier(
                keyId -> {
                    assertEquals("gca-key", keyId);
                    return (java.security.interfaces.RSAPublicKey) keyPair.getPublic();
                },
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        policy = new GcpConfidentialSpacePolicy(
                "https://sts.googleapis.com",
                SUBJECT,
                "GCP_AMD_SEV",
                IMAGE_DIGEST);
    }

    @Test
    void verifiesSignatureNonceFreshnessVmAndConfidentialSpaceImageClaims() throws Exception {
        var verified = verifier.verify(signedToken(CHALLENGE, IMAGE_DIGEST), CHALLENGE, policy);

        assertEquals(SUBJECT, verified.subject());
        assertEquals(IMAGE_DIGEST, verified.imageDigest());
        assertEquals(NOW.plusSeconds(300), verified.expiresAt());
    }

    @Test
    void rejectsBadSignatureNonceAndImageDigest() throws Exception {
        String signed = signedToken(CHALLENGE, IMAGE_DIGEST);
        int signatureOffset = signed.lastIndexOf('.') + 1;
        String tampered = signed.substring(0, signatureOffset)
                + (signed.charAt(signatureOffset) == 'A' ? 'B' : 'A')
                + signed.substring(signatureOffset + 1);
        assertThrows(GcpAttestationException.class,
                () -> verifier.verify(tampered, CHALLENGE, policy));
        assertThrows(GcpAttestationException.class,
                () -> verifier.verify(signed, Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[31]), policy));
        assertThrows(GcpAttestationException.class,
                () -> verifier.verify(
                        signed,
                        CHALLENGE,
                        new GcpConfidentialSpacePolicy(
                                policy.audience(),
                                SUBJECT,
                                policy.hardwareModel(),
                                "sha256:" + "b".repeat(64))));
    }

    @Test
    void rejectsVmIdentityAndDebugImages() throws Exception {
        String wrongIdentity = signedToken(CHALLENGE, IMAGE_DIGEST, "other-instance", false);
        String debugImage = signedToken(CHALLENGE, IMAGE_DIGEST, "cjae-gateway", true);

        assertThrows(GcpAttestationException.class,
                () -> verifier.verify(wrongIdentity, CHALLENGE, policy));
        assertThrows(GcpAttestationException.class,
                () -> verifier.verify(debugImage, CHALLENGE, policy));
    }

    @Test
    void requiresStableSupportAndRejectsUnexpectedOverrides() throws Exception {
        String expectedEnvironmentOverride = signedToken(CHALLENGE, IMAGE_DIGEST,
                "cjae-gateway", false, List.of("STABLE"),
                List.of("CJAE_RUNTIME_CONFIG_BASE64",
                        "CJAE_RUNTIME_CONFIG_SIGNATURE_BASE64"), null);
        String missingStable = signedToken(CHALLENGE, IMAGE_DIGEST, "cjae-gateway",
                false, List.of(), null, null);
        String wrongEnvironmentOverride = signedToken(CHALLENGE, IMAGE_DIGEST, "cjae-gateway",
                false, List.of("STABLE"), List.of("GATEWAY_OIDC_ISSUER"), null);
        String commandOverride = signedToken(CHALLENGE, IMAGE_DIGEST, "cjae-gateway",
                false, List.of("STABLE"), null, List.of("--spring.profiles.active=local"));

        assertEquals(SUBJECT, verifier.verify(
                expectedEnvironmentOverride, CHALLENGE, policy).subject());
        assertThrows(GcpAttestationException.class,
                () -> verifier.verify(missingStable, CHALLENGE, policy));
        assertThrows(GcpAttestationException.class,
                () -> verifier.verify(wrongEnvironmentOverride, CHALLENGE, policy));
        assertThrows(GcpAttestationException.class,
                () -> verifier.verify(commandOverride, CHALLENGE, policy));
    }

    private String signedToken(String nonce, String imageDigest) throws Exception {
        return signedToken(nonce, imageDigest, "cjae-gateway", false);
    }

    private String signedToken(
            String nonce, String imageDigest, String instanceName, boolean debug) throws Exception {
        return signedToken(nonce, imageDigest, instanceName, debug,
                List.of("STABLE"), null, null);
    }

    private String signedToken(
            String nonce,
            String imageDigest,
            String instanceName,
            boolean debug,
            List<String> supportAttributes,
            Object environmentOverride,
            Object commandOverride) throws Exception {
        String header = base64(objectMapper.writeValueAsBytes(
                Map.of("alg", "RS256", "kid", "gca-key")));
        Map<String, Object> claims = new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("iss", "https://confidentialcomputing.googleapis.com"),
                Map.entry("sub", SUBJECT),
                Map.entry("aud", List.of("https://sts.googleapis.com")),
                Map.entry("iat", NOW.getEpochSecond()),
                Map.entry("exp", NOW.plusSeconds(300).getEpochSecond()),
                Map.entry("nbf", NOW.minusSeconds(1).getEpochSecond()),
                Map.entry("eat_nonce", List.of("provider-nonce", nonce)),
                Map.entry("eat_profile", "https://cloud.google.com/confidential-computing/"
                        + "confidential-vm/docs/token-claims"),
                Map.entry("hwmodel", "GCP_AMD_SEV"),
                Map.entry("secboot", true),
                Map.entry("support_attributes", supportAttributes),
                Map.entry("swname", "CONFIDENTIAL_SPACE"),
                Map.entry("dbgstat", debug ? "enabled" : "disabled-since-boot"),
                Map.entry("submods", Map.of(
                        "gce", Map.of(
                                "project_id", "cjae-prod",
                                "zone", "us-central1-a",
                                "instance_name", instanceName),
                        "container", Map.of("image_digest", imageDigest)))));
        if (environmentOverride != null) {
            claims.put("env_override", environmentOverride);
        }
        if (commandOverride != null) {
            claims.put("cmd_override", commandOverride);
        }
        String payload = base64(objectMapper.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + base64(signer.sign());
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
