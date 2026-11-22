package org.opentron.backend.security.confidential.gateway.gcp;

import org.opentron.backend.security.confidential.ConfidentialRuntimeVerifier;

public final class GcpConfidentialRuntimeVerifier implements ConfidentialRuntimeVerifier {
    private final GcpGcaAttestationVerifier attestationVerifier;

    public GcpConfidentialRuntimeVerifier(GcpGcaAttestationVerifier attestationVerifier) {
        this.attestationVerifier = attestationVerifier;
    }

    @Override
    public void verifyCurrentRuntime() {
        attestationVerifier.attestCurrentRuntime();
    }
}
