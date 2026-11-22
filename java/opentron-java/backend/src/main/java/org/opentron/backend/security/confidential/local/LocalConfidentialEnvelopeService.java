package org.opentron.backend.security.confidential.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class LocalConfidentialEnvelopeService {
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int MAX_QUERY_LENGTH = 16_384;
    private static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "x-api-keys");

    private final ObjectMapper objectMapper;
    private final KeyPair keyPair;
    private final String keyId;

    public LocalConfidentialEnvelopeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072, new SecureRandom());
            this.keyPair = generator.generateKeyPair();
            this.keyId = UUID.randomUUID().toString();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not initialize the local confidential encryption key.", exception);
        }
    }

    public PublicKeyResponse publicKey() {
        return new PublicKeyResponse(
                keyId,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                "RSA-OAEP-256+A256GCM");
    }

    public DecryptedRequest decrypt(byte[] encodedEnvelope, String method, String requestPath) {
        try {
            Envelope envelope = objectMapper.readValue(encodedEnvelope, Envelope.class);
            if (envelope.keyId() == null || !keyId.equals(envelope.keyId())) {
                throw new StaleEnvelopeKeyException();
            }

            byte[] encryptedKey = Base64.getDecoder().decode(requireValue(envelope.encryptedKey()));
            byte[] iv = Base64.getDecoder().decode(requireValue(envelope.iv()));
            byte[] ciphertext = Base64.getDecoder().decode(requireValue(envelope.ciphertext()));
            if (iv.length != GCM_IV_BYTES || ciphertext.length < GCM_TAG_BITS / 8) {
                throw new EnvelopeException("Invalid confidential request envelope.");
            }

            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsa.init(Cipher.DECRYPT_MODE, keyPair.getPrivate(), new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            byte[] aesKey = rsa.doFinal(encryptedKey);
            if (aesKey.length != AES_KEY_BYTES) {
                throw new EnvelopeException("Invalid confidential request envelope.");
            }

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            aes.updateAAD(aad(method, requestPath, keyId));
            byte[] plaintext = aes.doFinal(ciphertext);
            DecryptedPayload payload = objectMapper.readValue(plaintext, DecryptedPayload.class);
            String query = payload.query() == null ? "" : payload.query();
            if (query.length() > MAX_QUERY_LENGTH) {
                throw new EnvelopeException("Confidential request query is too large.");
            }

            Map<String, String> headers = new LinkedHashMap<>();
            if (payload.headers() != null) {
                for (Map.Entry<String, String> entry : payload.headers().entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    String normalizedName = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                    if (!SENSITIVE_HEADERS.contains(normalizedName)) {
                        continue;
                    }
                    if (entry.getValue().indexOf('\r') >= 0 || entry.getValue().indexOf('\n') >= 0
                            || headers.putIfAbsent(normalizedName, entry.getValue()) != null) {
                        throw new EnvelopeException("Invalid confidential request envelope.");
                    }
                }
            }
            byte[] body = payload.body() == null ? new byte[0] : payload.body().getBytes(StandardCharsets.UTF_8);
            return new DecryptedRequest(body, query, Map.copyOf(headers));
        } catch (StaleEnvelopeKeyException exception) {
            throw exception;
        } catch (EnvelopeException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException | GeneralSecurityException exception) {
            throw new EnvelopeException("Invalid confidential request envelope.", exception);
        }
    }

    private static String requireValue(String value) {
        if (value == null || value.isBlank()) {
            throw new EnvelopeException("Invalid confidential request envelope.");
        }
        return value;
    }

    public static byte[] aad(String method, String requestPath, String keyId) {
        return (method.toUpperCase(java.util.Locale.ROOT) + "\n" + requestPath + "\n" + keyId)
                .getBytes(StandardCharsets.UTF_8);
    }

    public record PublicKeyResponse(String keyId, String publicKey, String algorithm) {
    }

    public record DecryptedRequest(byte[] body, String query, Map<String, String> headers) {
    }

    private record Envelope(String keyId, String encryptedKey, String iv, String ciphertext) {
    }

    private record DecryptedPayload(String body, String query, Map<String, String> headers) {
    }

    public static class EnvelopeException extends RuntimeException {
        public EnvelopeException(String message) {
            super(message);
        }

        public EnvelopeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class StaleEnvelopeKeyException extends EnvelopeException {
        public StaleEnvelopeKeyException() {
            super("The local confidential encryption key has changed.");
        }
    }
}
