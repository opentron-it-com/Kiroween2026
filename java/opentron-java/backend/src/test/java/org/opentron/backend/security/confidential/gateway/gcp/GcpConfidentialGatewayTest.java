package org.opentron.backend.security.confidential.gateway.gcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.URLDecoder;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class GcpConfidentialGatewayTest {
    private static final String CONFIDENTIAL_SPACE_IMAGE_DIGEST =
            "sha256:" + "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-17T08:30:00Z");
    private static final String GCA_AUDIENCE =
            "//iam.googleapis.com/projects/123456789/locations/global/"
                    + "workloadIdentityPools/pool/providers/gca";
    private static final String WIF_AUDIENCE = GCA_AUDIENCE;
    private static final String KMS_RESOURCE =
            "projects/trusted-project/locations/global/keyRings/runtime/cryptoKeys/context";
    private static final String JWT = "header.payload.signature";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsInvalidEvidenceBeforeStsOrKms() {
        RecordingTransport transport = new RecordingTransport(mapper);
        GcpGcaAttestationVerifier verifier = verifier(
                policy(GcpAttestationPolicy.HardwareModel.AMD_SEV),
                false,
                false);
        GcpWifKmsSecureContextKeyProvider keys = keyProvider(verifier, transport);

        assertThrows(GcpAttestationException.class, keys::currentKey);
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void acceptsAllowlistedAmdClaimsThenExchangesAttestationAndDecryptsKey() {
        RecordingTransport transport = new RecordingTransport(mapper);
        GcpGcaAttestationVerifier verifier = verifier(
                policy(GcpAttestationPolicy.HardwareModel.AMD_SEV),
                true,
                false);
        GcpWifKmsSecureContextKeyProvider keys = keyProvider(verifier, transport);

        var released = keys.currentKey();
        var cached = keys.findKey("context-dek");
        var missing = keys.findKey("another-key");

        assertEquals("context-dek", released.id());
        assertTrue(cached.isPresent());
        assertTrue(missing.isEmpty());
        assertEquals("AES", released.key().getAlgorithm());
        assertEquals(32, released.key().getEncoded().length);
        assertEquals(List.of(
                        URI.create("https://sts.googleapis.com/v1/token"),
                        URI.create("https://cloudkms.googleapis.com/v1/" + KMS_RESOURCE + ":decrypt")),
                transport.requests.stream().map(Request::uri).toList());
        assertTrue(transport.decodedForm.get().contains("subject_token=" + JWT));
        assertTrue(transport.authorization.get().startsWith("Bearer federated-access-token"));
    }

    @Test
    void acceptsTdxSpecificGceIdentityWithStableSupportClaim() {
        RecordingTransport transport = new RecordingTransport(mapper);
        GcpAttestationPolicy policy = policy(GcpAttestationPolicy.HardwareModel.INTEL_TDX);
        GcpGcaAttestationVerifier verifier = verifier(policy, true, true);

        Jwt result = verifier.attestCurrentRuntime();

        assertEquals("GCP_INTEL_TDX", result.getClaimAsString("hwmodel"));
        assertFalse(result.hasClaim("dbgstat"));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void verifiesClientSuppliedNonceForPublicAttestationChallenge() {
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[32]);
        GcpGcaAttestationVerifier verifier = verifier(
                policy(GcpAttestationPolicy.HardwareModel.AMD_SEV), true, false);

        Jwt result = verifier.attestCurrentRuntime(challenge);

        assertTrue(((List<?>) result.getClaim("eat_nonce")).contains(challenge));
    }

    @Test
    void requiresStableSupportAndRejectsUnexpectedRuntimeOverrides() {
        GcpAttestationPolicy policy = policy(GcpAttestationPolicy.HardwareModel.AMD_SEV);
        assertEquals("https://www.googleapis.com/compute/v1/projects/trusted-project/"
                        + "zones/us-central1-a/instances/trusted-instance",
                verifier(
                        policy, true, false, List.of("STABLE"),
                        List.of("CJAE_RUNTIME_CONFIG_BASE64",
                                "CJAE_RUNTIME_CONFIG_SIGNATURE_BASE64"),
                        null)
                        .attestCurrentRuntime().getSubject());
        assertThrows(GcpAttestationException.class,
                () -> verifier(policy, true, false, List.of(), null, null)
                        .attestCurrentRuntime());
        assertThrows(GcpAttestationException.class,
                () -> verifier(
                        policy, true, false, List.of("STABLE"),
                        List.of("GATEWAY_OIDC_ISSUER"), null)
                        .attestCurrentRuntime());
        assertThrows(GcpAttestationException.class,
                () -> verifier(
                        policy, true, false, List.of("STABLE"), null,
                        List.of("--spring.profiles.active=local"))
                        .attestCurrentRuntime());
    }

    @Test
    void requiresProductionConfidentialSpaceClaimsAndPinnedWorkloadDigest() {
        GcpAttestationPolicy pinnedPolicy = new GcpAttestationPolicy(
                GCA_AUDIENCE,
                GcpAttestationPolicy.HardwareModel.AMD_SEV,
                Set.of("trusted-project"),
                Set.of("us-central1-a"),
                Set.of("trusted-instance"),
                CONFIDENTIAL_SPACE_IMAGE_DIGEST);

        Jwt result = verifier(pinnedPolicy, true, false).attestCurrentRuntime();
        assertEquals("CONFIDENTIAL_SPACE", result.getClaimAsString("swname"));
        assertEquals("disabled-since-boot", result.getClaimAsString("dbgstat"));

        GcpAttestationPolicy wrongDigestPolicy = new GcpAttestationPolicy(
                GCA_AUDIENCE,
                GcpAttestationPolicy.HardwareModel.AMD_SEV,
                Set.of("trusted-project"),
                Set.of("us-central1-a"),
                Set.of("trusted-instance"),
                "sha256:" + "b".repeat(64));
        assertThrows(GcpAttestationException.class,
                () -> verifier(wrongDigestPolicy, true, false).attestCurrentRuntime());
    }

    @Test
    void rejectsInvalidClientAttestationNonceBeforeAcquiringEvidence() {
        GcpGcaAttestationVerifier verifier = new GcpGcaAttestationVerifier(
                (audience, nonce) -> {
                    throw new AssertionError("Invalid challenges must not acquire evidence.");
                },
                token -> {
                    throw new AssertionError("Invalid challenges must not be decoded.");
                },
                policy(GcpAttestationPolicy.HardwareModel.AMD_SEV),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new java.security.SecureRandom());

        assertThrows(GcpAttestationException.class,
                () -> verifier.attestCurrentRuntime("not-a-challenge"));
    }

    @Test
    void googleDecoderAcceptsPinnedRsaSignatureAndRejectsTampering() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String jwks = mapper.writeValueAsString(Map.of(
                "keys",
                List.of(Map.of(
                        "kty", "RSA",
                        "kid", "gca-key",
                        "alg", "RS256",
                        "use", "sig",
                        "n", base64Unsigned(((RSAPublicKey) keyPair.getPublic()).getModulus()),
                        "e", base64Unsigned(((RSAPublicKey) keyPair.getPublic()).getPublicExponent())))));
        GcpHttpTransport transport = (method, uri, headers, body, timeout, maxBytes) -> {
            assertEquals(GoogleGcaJwtDecoder.GOOGLE_GCA_JWKS_URI, uri);
            return new GcpHttpTransport.HttpResponse(200, jwks.getBytes(StandardCharsets.UTF_8));
        };
        GoogleGcaJwtDecoder decoder = new GoogleGcaJwtDecoder(transport, mapper);
        String signed = signedJwt(keyPair);

        assertEquals("https://confidentialcomputing.googleapis.com",
                decoder.decode(signed).getClaimAsString("iss"));
        int payloadStart = signed.indexOf('.') + 1;
        String tampered = signed.substring(0, payloadStart)
                + (signed.charAt(payloadStart) == 'a' ? 'b' : 'a')
                + signed.substring(payloadStart + 1);
        assertThrows(GcpAttestationException.class, () -> decoder.decode(tampered));
    }

    @Test
    void productionTransportRejectsNonHttpsExternalEndpoint() {
        JavaGcpHttpTransport transport = new JavaGcpHttpTransport(Duration.ofSeconds(1));
        assertThrows(
                GcpAttestationException.class,
                () -> transport.postOrGet(
                        "GET",
                        URI.create("http://example.org/metadata"),
                        Map.of(),
                        null,
                        Duration.ofSeconds(1),
                        1024));
    }

    private GcpWifKmsSecureContextKeyProvider keyProvider(
            GcpGcaAttestationVerifier verifier,
            RecordingTransport transport) {
        return new GcpWifKmsSecureContextKeyProvider(
                verifier,
                transport,
                mapper,
                WIF_AUDIENCE,
                KMS_RESOURCE,
                "context-dek",
                "AQIDBA==");
    }

    private GcpGcaAttestationVerifier verifier(
            GcpAttestationPolicy policy,
            boolean secureBoot,
            boolean tdx) {
        return verifier(policy, secureBoot, tdx, List.of("STABLE"), null, null);
    }

    private GcpGcaAttestationVerifier verifier(
            GcpAttestationPolicy policy,
            boolean secureBoot,
            boolean tdx,
            List<String> supportAttributes,
            Object environmentOverride,
            Object commandOverride) {
        AtomicReference<String> challenge = new AtomicReference<>();
        GcpAttestationTokenSource source = (audience, nonce) -> {
            assertEquals(policy.audience(), audience);
            challenge.set(nonce);
            return JWT;
        };
        GcpAttestationJwtDecoder decoder = token -> {
            assertEquals(JWT, token);
            return testJwt(challenge.get(), secureBoot, tdx, policy,
                    supportAttributes, environmentOverride, commandOverride);
        };
        return new GcpGcaAttestationVerifier(
                source,
                decoder,
                policy,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new java.security.SecureRandom());
    }

    private Jwt testJwt(
            String nonce,
            boolean secureBoot,
            boolean tdx,
            GcpAttestationPolicy policy,
            List<String> supportAttributes,
            Object environmentOverride,
            Object commandOverride) {
        Map<String, Object> gce = new LinkedHashMap<>();
        gce.put("zone", "us-central1-a");
        if (tdx) {
            gce.put("project_number", "123456789");
            gce.put("instance_id", "987654321");
        } else {
            gce.put("project_id", "trusted-project");
            gce.put("instance_name", "trusted-instance");
        }
        String subject = tdx
                ? "//compute.googleapis.com/projects/123456789/zones/us-central1-a/instances/987654321"
                : "https://www.googleapis.com/compute/v1/projects/trusted-project/"
                        + "zones/us-central1-a/instances/trusted-instance";
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", GcpGcaAttestationVerifier.GOOGLE_ISSUER);
        claims.put("sub", subject);
        claims.put("aud", GCA_AUDIENCE);
        claims.put("iat", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(300).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(1).getEpochSecond());
        claims.put("eat_nonce", tdx ? nonce : List.of("provider-nonce", nonce));
        claims.put("eat_profile", GcpGcaAttestationVerifier.EAT_PROFILE);
        claims.put("hwmodel", tdx ? "GCP_INTEL_TDX" : "GCP_AMD_SEV");
        claims.put("secboot", secureBoot);
        claims.put("support_attributes", supportAttributes);
        if (environmentOverride != null) {
            claims.put("env_override", environmentOverride);
        }
        if (commandOverride != null) {
            claims.put("cmd_override", commandOverride);
        }
        Map<String, Object> submods = new LinkedHashMap<>();
        submods.put("gce", gce);
        if (!policy.expectedImageDigest().isBlank()) {
            claims.put("swname", "CONFIDENTIAL_SPACE");
            claims.put("dbgstat", "disabled-since-boot");
            submods.put("container", Map.of(
                    "image_digest", CONFIDENTIAL_SPACE_IMAGE_DIGEST));
        }
        claims.put("submods", submods);
        return new Jwt(JWT, NOW, NOW.plusSeconds(300), Map.of("alg", "RS256"), claims);
    }

    private static GcpAttestationPolicy policy(GcpAttestationPolicy.HardwareModel model) {
        return new GcpAttestationPolicy(
                GCA_AUDIENCE,
                model,
                Set.of(model == GcpAttestationPolicy.HardwareModel.AMD_SEV
                        ? "trusted-project" : "123456789"),
                Set.of("us-central1-a"),
                Set.of(model == GcpAttestationPolicy.HardwareModel.AMD_SEV
                        ? "trusted-instance" : "987654321"));
    }

    private String signedJwt(KeyPair keyPair) throws Exception {
        String header = base64(mapper.writeValueAsBytes(Map.of("alg", "RS256", "kid", "gca-key")));
        String payload = base64(mapper.writeValueAsBytes(Map.of(
                "iss", GcpGcaAttestationVerifier.GOOGLE_ISSUER,
                "iat", NOW.getEpochSecond(),
                "exp", NOW.plusSeconds(300).getEpochSecond())));
        String signingInput = header + "." + payload;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + base64(signer.sign());
    }

    private static String base64Unsigned(java.math.BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return base64(bytes);
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static final class RecordingTransport implements GcpHttpTransport {
        private final ObjectMapper mapper;
        private final List<Request> requests = new ArrayList<>();
        private final AtomicReference<String> decodedForm = new AtomicReference<>();
        private final AtomicReference<String> authorization = new AtomicReference<>();

        private RecordingTransport(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public HttpResponse postOrGet(
                String method,
                URI uri,
                Map<String, String> headers,
                byte[] body,
                Duration timeout,
                int maximumResponseBytes) {
            requests.add(new Request(method, uri, headers, body));
            if (uri.equals(URI.create("https://sts.googleapis.com/v1/token"))) {
                String form = new String(body, StandardCharsets.UTF_8);
                decodedForm.set(URLDecoder.decode(form, StandardCharsets.UTF_8));
                return new HttpResponse(200, """
                        {"access_token":"federated-access-token","token_type":"Bearer","expires_in":3600}
                        """.getBytes(StandardCharsets.UTF_8));
            }
            if (uri.toString().startsWith("https://cloudkms.googleapis.com/v1/")) {
                authorization.set(headers.get("Authorization"));
                try {
                    byte[] dek = new byte[32];
                    for (int i = 0; i < dek.length; i++) {
                        dek[i] = (byte) i;
                    }
                    return new HttpResponse(200, mapper.writeValueAsBytes(
                            Map.of("plaintext", Base64.getEncoder().encodeToString(dek))));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            throw new AssertionError("Unexpected HTTP request: " + uri);
        }
    }

    private record Request(String method, URI uri, Map<String, String> headers, byte[] body) {}
}
