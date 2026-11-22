package org.opentron.backend.security.confidential.gateway.aws;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class NsmCbor {
    private static final ObjectMapper MAPPER = new ObjectMapper(new CBORFactory());

    private NsmCbor() {}

    static byte[] encodeAttestationRequest(byte[] nonce, byte[] publicKey) throws IOException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("user_data", null);
        fields.put("nonce", nonce);
        fields.put("public_key", publicKey);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("Attestation", fields);
        return MAPPER.writeValueAsBytes(request);
    }

    static byte[] decodeAttestationResponse(byte[] response) throws IOException {
        try (JsonParser parser = MAPPER.createParser(response)) {
            JsonNode root = MAPPER.readTree(parser);
            if (root == null || !root.isObject() || root.size() != 1) {
                throw new IOException("Expected a single NSM response entry.");
            }
            JsonNode attestation = root.get("Attestation");
            if (attestation == null || !attestation.isObject() || attestation.size() != 1) {
                throw new IOException("Expected an NSM attestation response.");
            }
            JsonNode document = attestation.get("document");
            if (document == null || !document.isBinary()) {
                throw new IOException("Expected a binary NSM attestation document.");
            }
            if (parser.nextToken() != null) {
                throw new IOException("Unexpected trailing data in NSM response.");
            }
            return document.binaryValue();
        }
    }
}
