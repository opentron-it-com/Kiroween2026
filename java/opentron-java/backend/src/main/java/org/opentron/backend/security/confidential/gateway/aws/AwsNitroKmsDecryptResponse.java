package org.opentron.backend.security.confidential.gateway.aws;

import java.util.Objects;

/**
 * KMS Recipient response. Plaintext is deliberately not represented here:
 * only CiphertextForRecipient may cross the transport boundary.
 */
public record AwsNitroKmsDecryptResponse(byte[] ciphertextForRecipient) {
    public static final int MAX_CIPHERTEXT_FOR_RECIPIENT_BYTES = 6 * 1024;

    public AwsNitroKmsDecryptResponse {
        Objects.requireNonNull(ciphertextForRecipient, "CiphertextForRecipient is required.");
        if (ciphertextForRecipient.length < 1
                || ciphertextForRecipient.length > MAX_CIPHERTEXT_FOR_RECIPIENT_BYTES) {
            throw new IllegalArgumentException("CiphertextForRecipient exceeds its size bound.");
        }
        ciphertextForRecipient = ciphertextForRecipient.clone();
    }

    @Override
    public byte[] ciphertextForRecipient() {
        return ciphertextForRecipient.clone();
    }
}
