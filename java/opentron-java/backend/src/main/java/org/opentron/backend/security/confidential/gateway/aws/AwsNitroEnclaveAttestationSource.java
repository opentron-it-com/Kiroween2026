package org.opentron.backend.security.confidential.gateway.aws;

/** Supplies an NSM attestation document binding its nonce and public_key claims. */
@FunctionalInterface
public interface AwsNitroEnclaveAttestationSource {
    byte[] attest(byte[] nonce, byte[] ephemeralRsaPublicKey);
}
