package org.opentron.backend.security.confidential.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LocalConfidentialEnvelopeServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void decryptsBodyQueryAndSensitiveHeadersForTheBoundRoute() throws Exception {
        LocalConfidentialEnvelopeService service = new LocalConfidentialEnvelopeService(objectMapper);
        byte[] envelope = encrypt(service, "POST", "/v1/agents/coordinate", "prompt",
                "request=private%20value",
                Map.of("Authorization", "Bearer secret", "X-API-Keys", "{\"openai\":\"secret\"}"));

        LocalConfidentialEnvelopeService.DecryptedRequest decrypted =
                service.decrypt(envelope, "POST", "/v1/agents/coordinate");

        assertArrayEquals("prompt".getBytes(StandardCharsets.UTF_8), decrypted.body());
        assertEquals("request=private%20value", decrypted.query());
        assertEquals("Bearer secret", decrypted.headers().get("authorization"));
        assertEquals("{\"openai\":\"secret\"}", decrypted.headers().get("x-api-keys"));
    }

    @Test
    void rejectsEnvelopeWhenMethodOrPathIsChanged() throws Exception {
        LocalConfidentialEnvelopeService service = new LocalConfidentialEnvelopeService(objectMapper);
        byte[] envelope = encrypt(service, "POST", "/v1/agents/coordinate", "prompt", "", Map.of());

        assertThrows(LocalConfidentialEnvelopeService.EnvelopeException.class,
                () -> service.decrypt(envelope, "POST", "/v1/agents/task"));
        assertThrows(LocalConfidentialEnvelopeService.EnvelopeException.class,
                () -> service.decrypt(envelope, "PUT", "/v1/agents/coordinate"));
    }

    private byte[] encrypt(
            LocalConfidentialEnvelopeService service,
            String method,
            String path,
            String body,
            String query,
            Map<String, String> headers) throws Exception {
        var publicKey = service.publicKey();
        byte[] aesKey = new byte[32];
        byte[] iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(aesKey);
        random.nextBytes(iv);

        var rsaPublicKey = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.publicKey())));
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, rsaPublicKey, new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] encryptedKey = rsa.doFinal(aesKey);

        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        aes.updateAAD(LocalConfidentialEnvelopeService.aad(method, path, publicKey.keyId()));
        byte[] ciphertext = aes.doFinal(objectMapper.writeValueAsBytes(new Payload(body, query, headers)));

        return objectMapper.writeValueAsBytes(new Envelope(
                publicKey.keyId(),
                Base64.getEncoder().encodeToString(encryptedKey),
                Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(ciphertext)));
    }

    private record Payload(String body, String query, Map<String, String> headers) {
    }

    private record Envelope(String keyId, String encryptedKey, String iv, String ciphertext) {
    }
}
