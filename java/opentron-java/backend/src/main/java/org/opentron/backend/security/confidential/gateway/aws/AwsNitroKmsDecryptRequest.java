package org.opentron.backend.security.confidential.gateway.aws;

import java.util.Objects;

/**
 * Bounded AWS KMS Decrypt request data, including its Recipient structure.
 */
public record AwsNitroKmsDecryptRequest(
        String keyId,
        byte[] ciphertextBlob,
        AwsNitroKmsRecipient recipient) {
    public static final int MAX_CIPHERTEXT_BLOB_BYTES = 6 * 1024;
    public static final int MAX_ATTESTATION_DOCUMENT_BYTES = 256 * 1024;
    public static final int MAX_REQUEST_BYTES = 272 * 1024;
    public static final String RSAES_OAEP_SHA_256 = "RSAES_OAEP_SHA_256";

    public AwsNitroKmsDecryptRequest {
        if (keyId == null || keyId.isBlank() || keyId.length() > 2048) {
            throw new IllegalArgumentException("A bounded KMS key ID is required.");
        }
        ciphertextBlob = copyAndRequireSize(
                ciphertextBlob, 1, MAX_CIPHERTEXT_BLOB_BYTES, "CiphertextBlob");
        Objects.requireNonNull(recipient, "KMS Recipient is required.");
        long requestBytes = (long) keyId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + ciphertextBlob.length + recipient.attestationDocument().length
                + recipient.keyEncryptionAlgorithm().length();
        if (requestBytes > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("KMS Decrypt request exceeds its size bound.");
        }
    }

    @Override
    public byte[] ciphertextBlob() {
        return ciphertextBlob.clone();
    }

    private static byte[] copyAndRequireSize(byte[] value, int min, int max, String field) {
        Objects.requireNonNull(value, field + " is required.");
        if (value.length < min || value.length > max) {
            throw new IllegalArgumentException(field + " exceeds its size bound.");
        }
        return value.clone();
    }
}
