package org.opentron.backend.security.confidential.gateway.aws;

import java.util.Arrays;

import org.opentron.backend.security.confidential.ConfidentialRuntimeVerifier;
import org.opentron.backend.security.confidential.gateway.SecureContextKey;

/**
 * Fails closed unless a fresh NSM Recipient decrypt succeeds and its response
 * can be unwrapped within this JVM. PCR/image checks depend on corresponding
 * key-policy conditions being provisioned externally; this verifier cannot
 * inspect or attest to that remote policy configuration.
 */
public final class AwsNitroConfidentialRuntimeVerifier implements ConfidentialRuntimeVerifier {
    private final AwsNitroEnclaveKeyReleaseService keyReleaseService;

    public AwsNitroConfidentialRuntimeVerifier(
            AwsNitroEnclaveKeyReleaseService keyReleaseService) {
        if (keyReleaseService == null) {
            throw new IllegalArgumentException("AWS Nitro key release service is required.");
        }
        this.keyReleaseService = keyReleaseService;
    }

    @Override
    public void verifyCurrentRuntime() {
        SecureContextKey released = keyReleaseService.releaseContextKey();
        byte[] encodedKey = released.key().getEncoded();
        if (!keyReleaseService.contextKeyId().equals(released.id())
                || encodedKey == null
                || !"AES".equalsIgnoreCase(released.key().getAlgorithm())
                || encodedKey.length != 32) {
            if (encodedKey != null) {
                Arrays.fill(encodedKey, (byte) 0);
            }
            throw new AwsNitroEnclaveException(
                    "AWS Nitro runtime verification received an unexpected context key.");
        }
        Arrays.fill(encodedKey, (byte) 0);
    }
}
