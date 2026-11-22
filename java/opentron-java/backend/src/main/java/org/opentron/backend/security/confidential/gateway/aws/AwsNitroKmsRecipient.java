package org.opentron.backend.security.confidential.gateway.aws;

import java.util.Objects;

/**
 * AWS KMS Decrypt {@code Recipient} parameter for an attested Nitro enclave.
 */
public record AwsNitroKmsRecipient(
        byte[] attestationDocument,
        String keyEncryptionAlgorithm) {

    public AwsNitroKmsRecipient {
        Objects.requireNonNull(attestationDocument, "AttestationDocument is required.");
        if (attestationDocument.length < 1
                || attestationDocument.length
                        > AwsNitroKmsDecryptRequest.MAX_ATTESTATION_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("AttestationDocument exceeds its size bound.");
        }
        if (!AwsNitroKmsDecryptRequest.RSAES_OAEP_SHA_256.equals(keyEncryptionAlgorithm)) {
            throw new IllegalArgumentException("Unsupported KMS recipient encryption algorithm.");
        }
        attestationDocument = attestationDocument.clone();
    }

    @Override
    public byte[] attestationDocument() {
        return attestationDocument.clone();
    }
}
