package org.opentron.backend.security.confidential.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalConfidentialEnvelopeFilterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void allowsCorsPreflightWithoutAnEnvelope() throws Exception {
        LocalConfidentialEnvelopeFilter filter = newFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/v1/agents/coordinate");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedChain = new AtomicBoolean();

        filter.doFilter(request, response, (wrappedRequest, wrappedResponse) -> reachedChain.set(true));

        assertTrue(reachedChain.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void rejectsPlaintextOnProtectedRoutes() throws Exception {
        LocalConfidentialEnvelopeFilter filter = newFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/agents/coordinate");
        request.setContent("{\"request\":\"plaintext\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedChain = new AtomicBoolean();

        filter.doFilter(request, response, (wrappedRequest, wrappedResponse) -> reachedChain.set(true));

        assertFalse(reachedChain.get());
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("encrypted_request_required"));
    }

    @Test
    void restoresDecryptedBodyAndPermitBeforeDispatch() throws Exception {
        LocalConfidentialEnvelopeService service = new LocalConfidentialEnvelopeService(objectMapper);
        LocalConfidentialEnvelopeFilter filter = new LocalConfidentialEnvelopeFilter(service, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/agents/coordinate");
        request.addHeader("X-Confidential-Envelope", "v1");
        request.setContent(encrypt(service, "/v1/agents/coordinate", "decrypted body"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedChain = new AtomicBoolean();

        filter.doFilter(request, response, (wrappedRequest, wrappedResponse) -> {
            reachedChain.set(true);
            HttpServletRequest httpRequest = (HttpServletRequest) wrappedRequest;
            assertEquals("decrypted body", new String(
                    httpRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            LocalConfidentialRequestContext.Permit permit =
                    LocalConfidentialRequestContext.capture(httpRequest);
            new LocalEncryptedRequestExecutionPolicy().verifyConfidentialExecution();
            assertEquals(service.publicKey().keyId(), permit.keyId());
        });

        assertTrue(reachedChain.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void restoresEncryptedQueryParametersBeforeDispatch() throws Exception {
        LocalConfidentialEnvelopeService service = new LocalConfidentialEnvelopeService(objectMapper);
        LocalConfidentialEnvelopeFilter filter = new LocalConfidentialEnvelopeFilter(service, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/orchestrate/task");
        request.addHeader("X-Confidential-Envelope", "v1");
        request.setContent(encrypt(service, "/v1/orchestrate/task", "", "task=private%20task&context=private"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedChain = new AtomicBoolean();

        filter.doFilter(request, response, (wrappedRequest, wrappedResponse) -> {
            reachedChain.set(true);
            HttpServletRequest httpRequest = (HttpServletRequest) wrappedRequest;
            assertEquals("private task", httpRequest.getParameter("task"));
            assertEquals("private", httpRequest.getParameter("context"));
        });

        assertTrue(reachedChain.get());
        assertEquals(200, response.getStatus());
    }

    private LocalConfidentialEnvelopeFilter newFilter() {
        ObjectMapper mapper = new ObjectMapper();
        return new LocalConfidentialEnvelopeFilter(
                new LocalConfidentialEnvelopeService(mapper), mapper);
    }

    private byte[] encrypt(
            LocalConfidentialEnvelopeService service,
            String path,
            String body) throws Exception {
        return encrypt(service, path, body, "");
    }

    private byte[] encrypt(
            LocalConfidentialEnvelopeService service,
            String path,
            String body,
            String query) throws Exception {
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
        aes.updateAAD(LocalConfidentialEnvelopeService.aad("POST", path, publicKey.keyId()));
        byte[] ciphertext = aes.doFinal(objectMapper.writeValueAsBytes(
                new Payload(body, query, Map.of())));
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
