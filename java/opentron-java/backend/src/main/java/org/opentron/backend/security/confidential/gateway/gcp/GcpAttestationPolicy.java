package org.opentron.backend.security.confidential.gateway.gcp;

import java.util.Set;

/**
 * Policy for Google Cloud Attestation EAT claims. Instance entries may match the
 * official GCA {@code instance_name} or {@code instance_id} value.
 */
public record GcpAttestationPolicy(
        String audience,
        HardwareModel hardwareModel,
        Set<String> allowedProjects,
        Set<String> allowedZones,
        Set<String> allowedInstances,
        String expectedImageDigest) {

    public enum HardwareModel {
        AMD_SEV("GCP_AMD_SEV"),
        INTEL_TDX("GCP_INTEL_TDX");

        private final String claimValue;

        HardwareModel(String claimValue) {
            this.claimValue = claimValue;
        }

        String claimValue() {
            return claimValue;
        }
    }

    public GcpAttestationPolicy {
        expectedImageDigest = expectedImageDigest == null ? "" : expectedImageDigest;
        if (audience == null || audience.isBlank() || audience.length() > 512) {
            throw new IllegalArgumentException("A GCA audience of at most 512 characters is required.");
        }
        if (hardwareModel == null) {
            throw new IllegalArgumentException("An explicit GCA hardware model is required.");
        }
        allowedProjects = immutableNonEmptySet(allowedProjects, "project");
        allowedZones = immutableNonEmptySet(allowedZones, "zone");
        allowedInstances = immutableNonEmptySet(allowedInstances, "instance");
        if (expectedImageDigest != null && !expectedImageDigest.isBlank()
                && !expectedImageDigest.matches("sha256:[A-Fa-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "Expected Confidential Space image digest must be a SHA-256 digest.");
        }
    }

    public GcpAttestationPolicy(
            String audience,
            HardwareModel hardwareModel,
            Set<String> allowedProjects,
            Set<String> allowedZones,
            Set<String> allowedInstances) {
        this(audience, hardwareModel, allowedProjects, allowedZones, allowedInstances, "");
    }

    private static Set<String> immutableNonEmptySet(Set<String> values, String label) {
        if (values == null || values.isEmpty()
                || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("At least one allowed " + label + " is required.");
        }
        return Set.copyOf(values);
    }
}
