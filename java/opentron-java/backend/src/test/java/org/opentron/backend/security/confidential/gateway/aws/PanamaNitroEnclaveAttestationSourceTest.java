package org.opentron.backend.security.confidential.gateway.aws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PanamaNitroEnclaveAttestationSourceTest {
    @Test
    void encodesNsmAttestationRequestUsingTheAwsCborShape() throws IOException {
        byte[] nonce = new byte[32];
        byte[] publicKey = new byte[] { 1, 2, 3 };
        byte[] encoded = NsmCbor.encodeAttestationRequest(nonce, publicKey);
        JsonNode fields = new ObjectMapper(new CBORFactory())
                .readTree(encoded)
                .get("Attestation");

        assertTrue(fields.get("user_data").isNull());
        assertArrayEquals(nonce, fields.get("nonce").binaryValue());
        assertArrayEquals(publicKey, fields.get("public_key").binaryValue());
    }

    @Test
    void decodesNsmAttestationResponseBinaryDocument() throws IOException {
        byte[] response = new byte[] {
                (byte) 0xa1, 0x6b, 'A', 't', 't', 'e', 's', 't', 'a', 't', 'i', 'o', 'n',
                (byte) 0xa1, 0x68, 'd', 'o', 'c', 'u', 'm', 'e', 'n', 't', 0x43, 4, 5, 6
        };

        assertArrayEquals(new byte[] { 4, 5, 6 }, NsmCbor.decodeAttestationResponse(response));
    }

    @Test
    void rejectsUnexpectedNsmResponseShape() {
        assertThrows(
                IOException.class,
                () -> NsmCbor.decodeAttestationResponse(
                        new byte[] { (byte) 0xa1, 0x65, 'E', 'r', 'r', 'o', 'r', (byte) 0xf6 }));
        assertThrows(
                IOException.class,
                () -> NsmCbor.decodeAttestationResponse(new byte[] { (byte) 0xa1 }));
        assertThrows(
                IOException.class,
                () -> NsmCbor.decodeAttestationResponse(new byte[] {
                        (byte) 0xa1, 0x6b, 'A', 't', 't', 'e', 's', 't', 'a', 't', 'i', 'o', 'n',
                        (byte) 0xa1, 0x68, 'd', 'o', 'c', 'u', 'm', 'e', 'n', 't', 0x41, 4, 0
                }));
    }

    @Test
    void forwardsNonceAndRecipientKeyToNativeNsmBoundary() {
        byte[] nonce = new byte[32];
        byte[] publicKey = new byte[] { 1, 2, 3 };
        byte[] document = new byte[] { 4, 5, 6 };
        var source = new PanamaNitroEnclaveAttestationSource((actualNonce, actualPublicKey) -> {
            assertArrayEquals(nonce, actualNonce);
            assertArrayEquals(publicKey, actualPublicKey);
            return document.clone();
        });

        assertArrayEquals(document, source.attest(nonce, publicKey));
    }

    @Test
    void rejectsInvalidInputsBeforeInvokingNativeCode() {
        var source = new PanamaNitroEnclaveAttestationSource((nonce, publicKey) -> {
            throw new AssertionError("Invalid NSM input must not cross the native boundary.");
        });

        assertThrows(IllegalArgumentException.class, () -> source.attest(null, new byte[] { 1 }));
        assertThrows(
                IllegalArgumentException.class,
                () -> source.attest(new byte[31], new byte[] { 1 }));
        assertThrows(
                IllegalArgumentException.class,
                () -> source.attest(new byte[32], new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> source.attest(new byte[32], new byte[16 * 1024 + 1]));
    }

    @Test
    void rejectsAndZeroesMalformedNativeDocuments() {
        byte[] oversized = new byte[0x3001];
        Arrays.fill(oversized, (byte) 0x5a);
        var source = new PanamaNitroEnclaveAttestationSource(
                (nonce, publicKey) -> oversized);

        assertThrows(
                AwsNitroEnclaveException.class,
                () -> source.attest(new byte[32], new byte[] { 1 }));
        assertTrue(Arrays.equals(new byte[oversized.length], oversized));
    }
}
