package org.opentron.backend.security.confidential.gateway.sdk;

import java.util.Objects;

public record GcpConfidentialSpacePolicy(
        String audience,
        String expectedSubject,
        String hardwareModel,
        String expectedImageDigest) {
    public GcpConfidentialSpacePolicy {
        if (audience == null || audience.isBlank() || audience.length() > 512
                || expectedSubject == null || expectedSubject.isBlank()
                || expectedSubject.length() > 1024
                || !expectedSubject.startsWith(
                        "https://www.googleapis.com/compute/v1/projects/")
                        && !expectedSubject.startsWith(
                                "//compute.googleapis.com/projects/")) {
            throw new IllegalArgumentException(
                    "Confidential Space audience and exact VM subject are required.");
        }
        if (!Objects.equals(hardwareModel, "GCP_AMD_SEV")
                && !Objects.equals(hardwareModel, "GCP_INTEL_TDX")) {
            throw new IllegalArgumentException(
                    "Confidential Space hardware model must be GCP_AMD_SEV or GCP_INTEL_TDX.");
        }
        if (expectedImageDigest == null
                || !expectedImageDigest.matches("sha256:[A-Fa-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "Confidential Space image digest must be a pinned SHA-256 digest.");
        }
    }
}
