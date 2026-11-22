package org.opentron.backend.security.confidential.gateway.aws;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;

public final class AwsNitroTlsKeyStoreProvider {
    private final KeyStore keyStore;

    public AwsNitroTlsKeyStoreProvider(
            AwsNitroEnclaveKeyReleaseService keyReleaseService,
            String encryptedKeyStoreBase64) {
        if (keyReleaseService == null || encryptedKeyStoreBase64 == null
                || encryptedKeyStoreBase64.isBlank()) {
            throw new IllegalArgumentException(
                    "A KMS-encrypted TLS PKCS#12 identity is required for the AWS gateway.");
        }

        byte[] encryptedKeyStore;
        try {
            encryptedKeyStore = Base64.getDecoder().decode(encryptedKeyStoreBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "AWS Nitro TLS identity must be valid Base64 KMS ciphertext.", exception);
        }

        byte[] keyStoreBytes = null;
        try {
            keyStoreBytes = keyReleaseService.releaseSecret(encryptedKeyStore);
            if (keyStoreBytes.length > 4096) {
                throw new IllegalStateException(
                        "KMS-released AWS Nitro TLS identity exceeds 4 KiB.");
            }
            KeyStore loaded = KeyStore.getInstance("PKCS12");
            loaded.load(new ByteArrayInputStream(keyStoreBytes), new char[0]);
            boolean hasPrivateKey = java.util.Collections.list(loaded.aliases()).stream()
                    .anyMatch(alias -> {
                        try {
                            if (!loaded.isKeyEntry(alias)
                                    || loaded.getKey(alias, new char[0]) == null
                                    || !(loaded.getCertificate(alias)
                                            instanceof X509Certificate certificate)) {
                                return false;
                            }
                            certificate.checkValidity();
                            return true;
                        } catch (Exception exception) {
                            throw new IllegalStateException(
                                    "AWS Nitro TLS identity contains an unreadable private key.",
                                    exception);
                        }
                    });
            if (!hasPrivateKey) {
                throw new IllegalStateException(
                        "AWS Nitro TLS identity has no passwordless PKCS#12 private key.");
            }
            keyStore = loaded;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to load the KMS-released AWS Nitro TLS identity.", exception);
        } finally {
            Arrays.fill(encryptedKeyStore, (byte) 0);
            if (keyStoreBytes != null) {
                Arrays.fill(keyStoreBytes, (byte) 0);
            }
        }
    }

    public KeyStore getKeyStore() {
        return keyStore;
    }
}
