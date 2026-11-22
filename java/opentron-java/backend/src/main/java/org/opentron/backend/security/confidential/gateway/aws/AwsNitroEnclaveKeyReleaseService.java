package org.opentron.backend.security.confidential.gateway.aws;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;

import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSEnvelopedData;
import org.bouncycastle.cms.RecipientInformation;
import org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient;
import org.opentron.backend.security.confidential.gateway.SecureContextKey;

/**
 * Obtains a fresh NSM document and submits a KMS Recipient Decrypt request.
 * Configured KMS key-policy conditions are the sole PCR/image enforcement
 * point; this class does not parse PCRs or provision/verify the remote policy.
 */
public final class AwsNitroEnclaveKeyReleaseService {
    private static final int NONCE_BYTES = 32;
    private static final int RSA_KEY_BITS = 2048;

    private final AwsNitroEnclaveAttestationSource attestationSource;
    private final ParentVsockKmsTransport kmsTransport;
    private final SecureRandom secureRandom;
    private final String kmsKeyId;
    private final byte[] ciphertextBlob;
    private final String contextKeyId;

    public AwsNitroEnclaveKeyReleaseService(
            AwsNitroEnclaveAttestationSource attestationSource,
            ParentVsockKmsTransport kmsTransport,
            SecureRandom secureRandom,
            String kmsKeyId,
            byte[] ciphertextBlob,
            String contextKeyId) {
        if (attestationSource == null || kmsTransport == null || secureRandom == null) {
            throw new IllegalArgumentException(
                    "NSM source, parent-vsock KMS transport, and secure random are required.");
        }
        if (kmsKeyId == null || kmsKeyId.isBlank() || kmsKeyId.length() > 2048) {
            throw new IllegalArgumentException("A bounded KMS key ID is required.");
        }
        if (ciphertextBlob == null || ciphertextBlob.length < 1
                || ciphertextBlob.length > AwsNitroKmsDecryptRequest.MAX_CIPHERTEXT_BLOB_BYTES) {
            throw new IllegalArgumentException("KMS ciphertext exceeds its size bound.");
        }
        if (contextKeyId == null || !contextKeyId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Context key identifier is invalid.");
        }
        this.attestationSource = attestationSource;
        this.kmsTransport = kmsTransport;
        this.secureRandom = secureRandom;
        this.kmsKeyId = kmsKeyId;
        this.ciphertextBlob = ciphertextBlob.clone();
        this.contextKeyId = contextKeyId;
    }

    public SecureContextKey releaseContextKey() {
        byte[] unwrappedKey = releaseSecret(ciphertextBlob);
        try {
            if (unwrappedKey.length != 32) {
                throw new AwsNitroEnclaveException(
                        "KMS Recipient plaintext is not a 256-bit AES key.");
            }
            return new SecureContextKey(
                    contextKeyId, new SecretKeySpec(unwrappedKey, "AES"));
        } finally {
            clear(unwrappedKey);
        }
    }

    public byte[] releaseSecret(byte[] encryptedSecret) {
        if (encryptedSecret == null || encryptedSecret.length < 1
                || encryptedSecret.length > AwsNitroKmsDecryptRequest.MAX_CIPHERTEXT_BLOB_BYTES) {
            throw new IllegalArgumentException("KMS ciphertext exceeds its size bound.");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] attestationDocument = null;
        byte[] ciphertextForRecipient = null;
        byte[] unwrappedKey = null;
        try {
            secureRandom.nextBytes(nonce);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_BITS, secureRandom);
            KeyPair recipientKeyPair = generator.generateKeyPair();
            byte[] publicKey = recipientKeyPair.getPublic().getEncoded();

            byte[] sourceNonce = nonce.clone();
            byte[] sourcePublicKey = publicKey.clone();
            try {
                attestationDocument = attestationSource.attest(sourceNonce, sourcePublicKey);
            } finally {
                Arrays.fill(sourceNonce, (byte) 0);
                Arrays.fill(sourcePublicKey, (byte) 0);
                Arrays.fill(publicKey, (byte) 0);
            }
            if (attestationDocument == null || attestationDocument.length < 1
                    || attestationDocument.length
                            > AwsNitroKmsDecryptRequest.MAX_ATTESTATION_DOCUMENT_BYTES) {
                throw new AwsNitroEnclaveException(
                        "NSM did not provide a bounded attestation document.");
            }

            AwsNitroKmsDecryptRequest request = new AwsNitroKmsDecryptRequest(
                    kmsKeyId,
                    encryptedSecret,
                    new AwsNitroKmsRecipient(
                            attestationDocument,
                            AwsNitroKmsDecryptRequest.RSAES_OAEP_SHA_256));
            AwsNitroKmsDecryptResponse response = kmsTransport.decrypt(request);
            if (response == null) {
                throw new AwsNitroEnclaveException("KMS Recipient response is missing.");
            }
            ciphertextForRecipient = response.ciphertextForRecipient();
            unwrappedKey =
                    decryptCiphertextForRecipient(ciphertextForRecipient, recipientKeyPair.getPrivate());
            byte[] released = unwrappedKey;
            unwrappedKey = null;
            return released;
        } catch (AwsNitroEnclaveException e) {
            throw e;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new AwsNitroEnclaveException(
                    "AWS Nitro attested key release failed closed.", e);
        } finally {
            Arrays.fill(nonce, (byte) 0);
            clear(attestationDocument);
            clear(ciphertextForRecipient);
            clear(unwrappedKey);
        }
    }

    public String contextKeyId() {
        return contextKeyId;
    }

    static byte[] decryptCiphertextForRecipient(
            byte[] ciphertextForRecipient, PrivateKey recipientPrivateKey) {
        try {
            CMSEnvelopedData envelope = new CMSEnvelopedData(ciphertextForRecipient);
            Collection<RecipientInformation> recipients =
                    envelope.getRecipientInfos().getRecipients();
            if (recipients.size() != 1) {
                throw new AwsNitroEnclaveException(
                        "AWS KMS Recipient envelope must contain exactly one recipient.");
            }
            return recipients.iterator()
                    .next()
                    .getContent(new JceKeyTransEnvelopedRecipient(recipientPrivateKey));
        } catch (CMSException exception) {
            throw new AwsNitroEnclaveException(
                    "AWS KMS Recipient CMS envelope could not be decrypted.", exception);
        }
    }

    private static void clear(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
