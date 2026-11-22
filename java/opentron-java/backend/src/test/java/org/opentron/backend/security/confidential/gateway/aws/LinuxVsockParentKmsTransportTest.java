package org.opentron.backend.security.confidential.gateway.aws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class LinuxVsockParentKmsTransportTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void copiesRequestBytesIntoNativeMemoryForThePanamaSocketCall() {
        byte[] input = new byte[] { 1, 2, 3, 4 };
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeBuffer =
                    LinuxVsockParentKmsTransport.copyToNative(arena, input);

            assertTrue(nativeBuffer.isNative());
            assertArrayEquals(input, nativeBuffer.toArray(ValueLayout.JAVA_BYTE));
            nativeBuffer.fill((byte) 0);
        }
    }

    @Test
    void encodesKmsRecipientRequestForParentProxy() throws Exception {
        var transport = new LinuxVsockParentKmsTransport(3, 8000, objectMapper);
        var request = new AwsNitroKmsDecryptRequest(
                "arn:aws:kms:us-east-1:123456789012:key/example",
                new byte[] { 1, 2, 3 },
                new AwsNitroKmsRecipient(
                        new byte[] { 4, 5 },
                        AwsNitroKmsDecryptRequest.RSAES_OAEP_SHA_256));

        byte[] frame = transport.encodeRequest(request);
        try {
            JsonNode json = objectMapper.readTree(frame);
            assertEquals(1, json.path("version").asInt());
            assertEquals("kms:Decrypt", json.path("operation").asText());
            JsonNode kmsRequest = json.path("request");
            assertEquals(request.keyId(), kmsRequest.path("KeyId").asText());
            assertEquals(
                    Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 }),
                    kmsRequest.path("CiphertextBlob").asText());
            assertEquals(
                    Base64.getEncoder().encodeToString(new byte[] { 4, 5 }),
                    kmsRequest.path("Recipient").path("AttestationDocument").asText());
        } finally {
            java.util.Arrays.fill(frame, (byte) 0);
        }
    }

    @Test
    void parsesOnlyCiphertextForRecipientFromVersionedResponse() throws Exception {
        var transport = new LinuxVsockParentKmsTransport(3, 8000, objectMapper);
        byte[] response = objectMapper.writeValueAsBytes(
                java.util.Map.of(
                        "version", 1,
                        "response",
                        java.util.Map.of(
                                "CiphertextForRecipient",
                                Base64.getEncoder().encodeToString(new byte[] { 9, 8, 7 }))));

        assertArrayEquals(
                new byte[] { 9, 8, 7 },
                transport.decodeResponse(response).ciphertextForRecipient());
    }

    @Test
    void rejectsInvalidProxyResponsesAndEndpointConfiguration() throws Exception {
        var transport = new LinuxVsockParentKmsTransport(3, 8000, objectMapper);

        assertThrows(
                AwsNitroEnclaveException.class,
                () -> transport.decodeResponse("{\"version\":2}".getBytes()));
        assertThrows(
                AwsNitroEnclaveException.class,
                () -> transport.decodeResponse(
                        "{\"version\":1,\"response\":{\"CiphertextForRecipient\":\"%%%\"}}"
                                .getBytes()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LinuxVsockParentKmsTransport(-1, 8000, objectMapper));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LinuxVsockParentKmsTransport(3, 65536, objectMapper));
    }
}
