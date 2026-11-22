package org.opentron.backend.security.confidential.gateway.gcp;

import java.security.SecureRandom;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/** Produces and validates fresh Google Cloud Attestation evidence against local policy. */
public final class GcpGcaAttestationVerifier {
    static final String GOOGLE_ISSUER = "https://confidentialcomputing.googleapis.com";
    static final String EAT_PROFILE =
            "https://cloud.google.com/confidential-computing/confidential-vm/docs/token-claims";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Duration MAX_TOKEN_LIFETIME = Duration.ofHours(2);
    private static final Duration MAX_TOKEN_AGE = Duration.ofMinutes(10);
    private static final int NONCE_BYTES = 32;
    private static final Set<String> EXPECTED_ENV_OVERRIDES = Set.of(
            "CJAE_RUNTIME_CONFIG_BASE64",
            "CJAE_RUNTIME_CONFIG_SIGNATURE_BASE64");

    private final GcpAttestationTokenSource tokenSource;
    private final GcpAttestationJwtDecoder jwtDecoder;
    private final GcpAttestationPolicy policy;
    private final Clock clock;
    private final SecureRandom secureRandom;

    public GcpGcaAttestationVerifier(
            GcpAttestationTokenSource tokenSource,
            GcpAttestationJwtDecoder jwtDecoder,
            GcpAttestationPolicy policy,
            Clock clock,
            SecureRandom secureRandom) {
        this.tokenSource = tokenSource;
        this.jwtDecoder = jwtDecoder;
        this.policy = policy;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    public Jwt attestCurrentRuntime() {
        byte[] random = new byte[NONCE_BYTES];
        secureRandom.nextBytes(random);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        java.util.Arrays.fill(random, (byte) 0);
        return attestCurrentRuntime(nonce);
    }

    public Jwt attestCurrentRuntime(String clientChallenge) {
        if (!isClientChallenge(clientChallenge)) {
            reject("Client attestation challenge must be a canonical 256-bit base64url nonce.");
        }
        String token = tokenSource.fetch(policy.audience(), clientChallenge);
        Jwt jwt = jwtDecoder.decode(token);
        if (jwt == null) {
            reject("GCA JWT decoder returned no verified claims.");
        }
        validateClaims(jwt, clientChallenge);
        return jwt;
    }

    private static boolean isClientChallenge(String challenge) {
        if (challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}")) {
            return false;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(challenge);
            try {
                return decoded.length == NONCE_BYTES
                        && Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(decoded).equals(challenge);
            } finally {
                java.util.Arrays.fill(decoded, (byte) 0);
            }
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private void validateClaims(Jwt jwt, String expectedNonce) {
        if (!GOOGLE_ISSUER.equals(jwt.getClaimAsString("iss"))) {
            reject("GCA issuer does not match the pinned Google issuer.");
        }
        if (!hasAudience(jwt.getClaims().get("aud"), policy.audience())) {
            reject("GCA audience does not match policy.");
        }
        if (!EAT_PROFILE.equals(jwt.getClaimAsString("eat_profile"))) {
            reject("Evidence does not identify the official Google CVM EAT profile.");
        }
        validateTimes(jwt);
        if (!hasExpectedNonce(jwt.getClaims().get("eat_nonce"), expectedNonce)) {
            reject("GCA nonce does not match the fresh request challenge.");
        }
        if (!policy.hardwareModel().claimValue().equals(jwt.getClaimAsString("hwmodel"))) {
            reject("GCA hardware model does not match the selected policy.");
        }
        if (!Boolean.TRUE.equals(jwt.getClaim("secboot"))) {
            reject("GCA evidence does not assert that Secure Boot is enabled.");
        }
        validateLaunchPolicyClaims(jwt);
        validateGceIdentity(jwt);
        validateConfidentialSpaceWorkload(jwt);
    }

    private static void validateLaunchPolicyClaims(Jwt jwt) {
        Object supportAttributes = jwt.getClaims().get("support_attributes");
        if (!(supportAttributes instanceof Collection<?> values)
                || !values.contains("STABLE")) {
            reject("GCA evidence does not assert the STABLE support attribute.");
        }
        validateEnvironmentOverrides(overrideClaim(jwt, "env_override"));
        Object commandOverride = overrideClaim(jwt, "cmd_override");
        if (commandOverride != null
                && !Boolean.FALSE.equals(commandOverride)
                && !(commandOverride instanceof Collection<?> values && values.isEmpty())
                && !(commandOverride instanceof String value && value.isEmpty())) {
            reject("GCA evidence allows an unexpected command override.");
        }
    }

    private static Object overrideClaim(Jwt jwt, String name) {
        Object claim = jwt.getClaims().get(name);
        Map<String, Object> submods = jwt.getClaim("submods");
        Object containerValue = submods == null ? null : submods.get("container");
        Object containerClaim = containerValue instanceof Map<?, ?> container
                ? container.get(name) : null;
        if (claim != null && containerClaim != null && !claim.equals(containerClaim)) {
            reject("GCA evidence contains conflicting " + name + " claims.");
        }
        return claim == null ? containerClaim : claim;
    }

    private static void validateEnvironmentOverrides(Object claim) {
        if (claim == null) {
            return;
        }
        Set<String> values = new HashSet<>();
        if (claim instanceof String value) {
            for (String item : value.split(",", -1)) {
                if (item.isBlank() || !values.add(item)) {
                    reject("GCA evidence contains an invalid environment override claim.");
                }
            }
        } else if (claim instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (!(item instanceof String value) || value.isBlank() || !values.add(value)) {
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

    private void validateConfidentialSpaceWorkload(Jwt jwt) {
        if (policy.expectedImageDigest().isBlank()) {
            return;
        }
        if (!"CONFIDENTIAL_SPACE".equals(jwt.getClaimAsString("swname"))
                || !"disabled-since-boot".equals(jwt.getClaimAsString("dbgstat"))) {
            reject("GCA evidence must identify a production Confidential Space image.");
        }
        Map<String, Object> submods = jwt.getClaim("submods");
        Object containerValue = submods == null ? null : submods.get("container");
        if (!(containerValue instanceof Map<?, ?> container)
                || !policy.expectedImageDigest().equals(string(container.get("image_digest")))) {
            reject("GCA workload image digest does not match the pinned production image.");
        }
    }

    private void validateTimes(Jwt jwt) {
        Instant now = clock.instant();
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        Instant notBefore = instantClaim(jwt.getClaims().get("nbf"));
        if (issuedAt == null || expiresAt == null || notBefore == null
                || issuedAt.isAfter(now.plus(CLOCK_SKEW))
                || issuedAt.isBefore(now.minus(MAX_TOKEN_AGE))
                || !expiresAt.isAfter(now.minus(CLOCK_SKEW))
                || !notBefore.isBefore(now.plus(CLOCK_SKEW))
                || !expiresAt.isAfter(issuedAt)
                || Duration.between(issuedAt, expiresAt).compareTo(MAX_TOKEN_LIFETIME) > 0) {
            reject("GCA issue, not-before, or expiration times are invalid or stale.");
        }
    }

    private void validateGceIdentity(Jwt jwt) {
        Map<String, Object> submods = jwt.getClaim("submods");
        if (submods == null || !(submods.get("gce") instanceof Map<?, ?>)) {
            reject("GCA evidence does not contain the required submods.gce identity.");
        }
        Map<?, ?> gce = (Map<?, ?>) submods.get("gce");
        String zone = string(gce.get("zone"));
        String project;
        String instance;
        String expectedSubject;
        if (policy.hardwareModel() == GcpAttestationPolicy.HardwareModel.AMD_SEV) {
            project = string(gce.get("project_id"));
            instance = string(gce.get("instance_name"));
            if (project == null || instance == null) {
                reject("AMD SEV GCA identity is missing project_id or instance_name.");
            }
            expectedSubject = "https://www.googleapis.com/compute/v1/projects/"
                    + project + "/zones/" + zone + "/instances/" + instance;
        } else {
            project = string(gce.get("project_number"));
            instance = string(gce.get("instance_id"));
            if (project == null || instance == null) {
                reject("Intel TDX GCA identity is missing project_number or instance_id.");
            }
            expectedSubject = "//compute.googleapis.com/projects/"
                    + project + "/zones/" + zone + "/instances/" + instance;
        }
        if (zone == null || !policy.allowedZones().contains(zone)
                || !policy.allowedProjects().contains(project)
                || !policy.allowedInstances().contains(instance)
                || !expectedSubject.equals(jwt.getSubject())) {
            reject("GCA project, zone, instance, or subject is outside the allowlist.");
        }
    }

    private static boolean hasAudience(Object claim, String expected) {
        if (claim instanceof String value) {
            return expected.equals(value);
        }
        return claim instanceof List<?> values && values.stream().anyMatch(expected::equals);
    }

    private static boolean hasExpectedNonce(Object claim, String expected) {
        if (claim instanceof String value) {
            return validNonce(value) && expected.equals(value);
        }
        if (!(claim instanceof List<?> values) || values.isEmpty() || values.size() > 6) {
            return false;
        }
        boolean matched = false;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Object item : values) {
            if (!(item instanceof String value) || !validNonce(value) || !seen.add(value)) {
                return false;
            }
            matched |= expected.equals(value);
        }
        return matched;
    }

    private static boolean validNonce(String nonce) {
        int byteLength = nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return byteLength >= 10 && byteLength <= 74;
    }

    private static String string(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Instant instantClaim(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger)) {
            return null;
        }
        try {
            long epochSeconds = value instanceof BigInteger integer
                    ? integer.longValueExact() : ((Number) value).longValue();
            return Instant.ofEpochSecond(epochSeconds);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void reject(String message) {
        throw new GcpAttestationException(message);
    }
}
