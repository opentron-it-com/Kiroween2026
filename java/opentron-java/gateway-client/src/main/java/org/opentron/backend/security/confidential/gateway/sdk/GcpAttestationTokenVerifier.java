package org.opentron.backend.security.confidential.gateway.sdk;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class GcpAttestationTokenVerifier {
    private static final String GOOGLE_ISSUER =
            "https://confidentialcomputing.googleapis.com";
    private static final String EAT_PROFILE =
            "https://cloud.google.com/confidential-computing/confidential-vm/docs/token-claims";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Duration MAX_TOKEN_LIFETIME = Duration.ofHours(2);
    private static final Duration MAX_TOKEN_AGE = Duration.ofMinutes(10);
    private static final int MAX_TOKEN_BYTES = 64 * 1024;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Set<String> EXPECTED_ENV_OVERRIDES = Set.of(
            "CJAE_RUNTIME_CONFIG_BASE64",
            "CJAE_RUNTIME_CONFIG_SIGNATURE_BASE64");

    private final GcpAttestationSigningKeyResolver keyResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    GcpAttestationTokenVerifier(
            GcpAttestationSigningKeyResolver keyResolver,
            ObjectMapper objectMapper,
            Clock clock) {
        this.keyResolver = keyResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    VerifiedGcpAttestation verify(
            String compactToken, String expectedChallenge, GcpConfidentialSpacePolicy policy) {
        if (compactToken == null
                || compactToken.getBytes(StandardCharsets.US_ASCII).length > MAX_TOKEN_BYTES) {
            reject("GCA attestation token is missing or oversized.");
        }
        validateChallenge(expectedChallenge);
        String[] parts = compactToken.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()
                || parts[2].isEmpty()) {
            reject("GCA attestation token is not a compact JWT.");
        }

        try {
            JsonNode header = parseObject(parts[0]);
            validateHeader(header);
            RSAPublicKey key = keyResolver.resolve(header.path("kid").textValue());
            verifySignature(parts, key);

            JsonNode claims = parseObject(parts[1]);
            validateClaims(claims, expectedChallenge, policy);
            return new VerifiedGcpAttestation(
                    Instant.ofEpochSecond(requiredLong(claims, "exp")),
                    claims.path("sub").textValue(),
                    claims.path("submods").path("container")
                            .path("image_digest").textValue());
        } catch (GcpAttestationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new GcpAttestationException(
                    "GCA attestation token signature or claims are invalid.", failure);
        }
    }

    private JsonNode parseObject(String encodedSegment) throws Exception {
        byte[] bytes = Base64.getUrlDecoder().decode(encodedSegment);
        try (JsonParser parser = objectMapper.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode result = objectMapper.readTree(parser);
            if (result == null || !result.isObject() || parser.nextToken() != null) {
                reject("GCA JWT segments must contain one JSON object.");
            }
            return result;
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void validateHeader(JsonNode header) {
        if (!"RS256".equals(text(header, "alg"))) {
            reject("GCA JWT algorithm is not the pinned RS256 algorithm.");
        }
        String keyId = text(header, "kid");
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            reject("GCA JWT key identifier is invalid.");
        }
        var fields = header.fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            if (!Set.of("alg", "kid", "typ").contains(name)) {
                reject("GCA JWT contains unsupported JOSE header parameters.");
            }
        }
    }

    private void validateClaims(
            JsonNode claims, String expectedChallenge, GcpConfidentialSpacePolicy policy) {
        if (!GOOGLE_ISSUER.equals(text(claims, "iss"))
                || !EAT_PROFILE.equals(text(claims, "eat_profile"))
                || !audienceMatches(claims.path("aud"), policy.audience())
                || !policy.hardwareModel().equals(text(claims, "hwmodel"))
                || !Boolean.TRUE.equals(claims.path("secboot").booleanValue())
                || !"CONFIDENTIAL_SPACE".equals(text(claims, "swname"))
                || !"disabled-since-boot".equals(text(claims, "dbgstat"))
                || !policy.expectedSubject().equals(text(claims, "sub"))) {
            reject("GCA evidence does not satisfy the configured Confidential Space policy.");
        }
        validateLaunchPolicyClaims(claims);
        validateTimes(claims);
        validateNonce(claims.path("eat_nonce"), expectedChallenge);
        validateWorkload(claims.path("submods"), policy.expectedImageDigest());
        validateComputeSubject(claims.path("submods").path("gce"), policy);
    }

    private static void validateLaunchPolicyClaims(JsonNode claims) {
        JsonNode supportAttributes = claims.path("support_attributes");
        boolean stable = false;
        if (supportAttributes.isArray()) {
            for (JsonNode attribute : supportAttributes) {
                stable |= attribute.isTextual() && "STABLE".equals(attribute.textValue());
            }
        }
        if (!stable) {
            reject("GCA evidence does not assert the STABLE support attribute.");
        }
        validateEnvironmentOverrides(overrideClaim(claims, "env_override"));
        JsonNode commandOverride = overrideClaim(claims, "cmd_override");
        if (!commandOverride.isMissingNode() && !commandOverride.isNull()
                && !(commandOverride.isBoolean() && !commandOverride.booleanValue())
                && !(commandOverride.isArray() && commandOverride.isEmpty())
                && !(commandOverride.isTextual() && commandOverride.textValue().isEmpty())) {
            reject("GCA evidence allows an unexpected command override.");
        }
    }

    private static JsonNode overrideClaim(JsonNode claims, String name) {
        JsonNode topLevel = claims.get(name);
        JsonNode container = claims.path("submods").path("container").get(name);
        if (topLevel != null && container != null && !topLevel.equals(container)) {
            reject("GCA evidence contains conflicting " + name + " claims.");
        }
        if (topLevel != null) {
            return topLevel;
        }
        return container == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                : container;
    }

    private static void validateEnvironmentOverrides(JsonNode claim) {
        if (claim.isMissingNode() || claim.isNull()) {
            return;
        }
        Set<String> values = new HashSet<>();
        if (claim.isTextual()) {
            for (String value : claim.textValue().split(",", -1)) {
                if (value.isBlank() || !values.add(value)) {
                    reject("GCA evidence contains an invalid environment override claim.");
                }
            }
        } else if (claim.isArray()) {
            for (JsonNode value : claim) {
                if (!value.isTextual() || value.textValue().isBlank()
                        || !values.add(value.textValue())) {
                    reject("GCA evidence contains an invalid environment override claim.");
                }
            }
        } else {
            reject("GCA evidence contains an invalid environment override claim.");
        }
        if (!EXPECTED_ENV_OVERRIDES.equals(values)) {
            reject("GCA evidence allows unexpected environment overrides.");
        }
    }

    private void validateTimes(JsonNode claims) {
        long issuedAtSeconds = requiredLong(claims, "iat");
        long expiresAtSeconds = requiredLong(claims, "exp");
        long notBeforeSeconds = requiredLong(claims, "nbf");
        Instant now = clock.instant();
        Instant issuedAt = Instant.ofEpochSecond(issuedAtSeconds);
        Instant expiresAt = Instant.ofEpochSecond(expiresAtSeconds);
        Instant notBefore = Instant.ofEpochSecond(notBeforeSeconds);
        if (issuedAt.isAfter(now.plus(CLOCK_SKEW))
                || issuedAt.isBefore(now.minus(MAX_TOKEN_AGE))
                || !expiresAt.isAfter(now.minus(CLOCK_SKEW))
                || !notBefore.isBefore(now.plus(CLOCK_SKEW))
                || !expiresAt.isAfter(issuedAt)
                || Duration.between(issuedAt, expiresAt).compareTo(MAX_TOKEN_LIFETIME) > 0) {
            reject("GCA attestation token is stale or has invalid validity times.");
        }
    }

    private static void validateNonce(JsonNode claim, String expectedChallenge) {
        if (claim.isTextual()) {
            if (!expectedChallenge.equals(claim.textValue())) {
                reject("GCA attestation nonce does not match the client challenge.");
            }
            return;
        }
        if (!claim.isArray() || claim.isEmpty() || claim.size() > 6) {
            reject("GCA attestation nonce claim is invalid.");
        }
        Set<String> seen = new HashSet<>();
        boolean matched = false;
        for (JsonNode nonce : claim) {
            if (!nonce.isTextual() || nonce.textValue().isBlank()
                    || nonce.textValue().getBytes(StandardCharsets.UTF_8).length > 88
                    || !seen.add(nonce.textValue())) {
                reject("GCA attestation nonce claim is invalid.");
            }
            matched |= expectedChallenge.equals(nonce.textValue());
        }
        if (!matched) {
            reject("GCA attestation nonce does not match the client challenge.");
        }
    }

    private static void validateWorkload(JsonNode submods, String expectedImageDigest) {
        if (!submods.isObject()
                || !expectedImageDigest.equals(
                        text(submods.path("container"), "image_digest"))) {
            reject("GCA workload image digest does not match the pinned image.");
        }
    }

    private static void validateComputeSubject(
            JsonNode gce, GcpConfidentialSpacePolicy policy) {
        if (!gce.isObject()) {
            reject("GCA evidence does not include Compute Engine identity claims.");
        }
        String project;
        String instance;
        String subject;
        String zone = text(gce, "zone");
        if ("GCP_AMD_SEV".equals(policy.hardwareModel())) {
            project = text(gce, "project_id");
            instance = text(gce, "instance_name");
            subject = "https://www.googleapis.com/compute/v1/projects/"
                    + project + "/zones/" + zone + "/instances/" + instance;
        } else {
            project = text(gce, "project_number");
            instance = text(gce, "instance_id");
            subject = "//compute.googleapis.com/projects/"
                    + project + "/zones/" + zone + "/instances/" + instance;
        }
        if (isBlank(project) || isBlank(instance) || isBlank(zone)
                || !policy.expectedSubject().equals(subject)) {
            reject("GCA Compute Engine identity does not match the configured VM.");
        }
    }

    private static boolean audienceMatches(JsonNode audience, String expected) {
        if (audience.isTextual()) {
            return expected.equals(audience.textValue());
        }
        if (audience.isArray()) {
            for (JsonNode value : audience) {
                if (value.isTextual() && expected.equals(value.textValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long requiredLong(JsonNode claims, String name) {
        JsonNode value = claims.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            reject("GCA attestation token is missing a valid " + name + " claim.");
        }
        return value.longValue();
    }

    private static String text(JsonNode object, String name) {
        JsonNode value = object.get(name);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static void validateChallenge(String challenge) {
        if (challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}")) {
            reject("Client GCA challenge must be a canonical 256-bit base64url nonce.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(challenge);
        } catch (IllegalArgumentException failure) {
            reject("Client GCA challenge is malformed.");
            return;
        }
        try {
            if (decoded.length != 32
                    || !Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(decoded).equals(challenge)) {
                reject("Client GCA challenge must be a canonical 256-bit base64url nonce.");
            }
        } finally {
            java.util.Arrays.fill(decoded, (byte) 0);
        }
    }

    private static void verifySignature(String[] parts, RSAPublicKey key) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(key);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
            reject("GCA attestation token signature is invalid.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void reject(String message) {
        throw new GcpAttestationException(message);
    }

    record VerifiedGcpAttestation(Instant expiresAt, String subject, String imageDigest) {
    }
}
