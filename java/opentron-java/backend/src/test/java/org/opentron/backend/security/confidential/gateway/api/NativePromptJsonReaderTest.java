package org.opentron.backend.security.confidential.gateway.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.ConfidentialSegment;
import org.opentron.backend.security.confidential.gateway.NativeUtf8CharSequence;

class NativePromptJsonReaderTest {
    @Test
    void decodesJsonEscapesAndUtf8DirectlyIntoNativePromptMemory() throws Exception {
        byte[] body = " {\"text\":\"Contact ada\\u0040example.com 😀\"}"
                .getBytes(StandardCharsets.UTF_8);

        try (ConfidentialSegment prompt = NativePromptJsonReader.readText(
                new ByteArrayInputStream(body), body.length)) {
            assertEquals("Contact ada@example.com 😀",
                    new NativeUtf8CharSequence(prompt).toString());
        }
    }

    @Test
    void rejectsMalformedJsonAndUnpairedSurrogateEscapes() {
        byte[] malformed = "{\"text\":\"broken\" trailing}".getBytes(StandardCharsets.UTF_8);
        byte[] surrogate = "{\"text\":\"\\uD800\"}".getBytes(StandardCharsets.UTF_8);

        assertThrows(NativePromptFormatException.class,
                () -> NativePromptJsonReader.readText(
                        new ByteArrayInputStream(malformed), malformed.length));
        assertThrows(NativePromptFormatException.class,
                () -> NativePromptJsonReader.readText(
                        new ByteArrayInputStream(surrogate), surrogate.length));
    }

    @Test
    void rejectsNonTextFieldsAndOversizedBodies() {
        byte[] body = "{\"text\":\"x\",\"other\":\"y\"}".getBytes(StandardCharsets.UTF_8);

        assertThrows(NativePromptFormatException.class,
                () -> NativePromptJsonReader.readText(
                        new ByteArrayInputStream(body), body.length));
        assertThrows(IllegalArgumentException.class,
                () -> NativePromptJsonReader.readText(
                        new ByteArrayInputStream(new byte[0]), Long.MAX_VALUE));
    }

    @Test
    void rejectsBodiesWhoseDeclaredLengthCannotHoldDecodedText() {
        byte[] body = "{\"text\":\"long enough\"}".getBytes(StandardCharsets.UTF_8);

        assertThrows(NativePromptFormatException.class,
                () -> NativePromptJsonReader.readText(
                        new ByteArrayInputStream(body), 1));
    }

    @Test
    void readsBatchPromptsIntoSlicesOfOneNativeAllocation() throws Exception {
        byte[] body = ("{\"tenantId\":\"tenant-one\","
                + "\"segments\":[\"ada@example.com\",\"😀 212-555-0199\"]}")
                .getBytes(StandardCharsets.UTF_8);

        try (NativePromptJsonReader.NativePromptBatch batch =
                NativePromptJsonReader.readSegments(
                        new ByteArrayInputStream(body), body.length)) {
            assertEquals(List.of("ada@example.com", "😀 212-555-0199"),
                    batch.segments().stream()
                            .map(segment -> new NativeUtf8CharSequence(segment).toString())
                            .toList());
        }
    }

    @Test
    void rejectsEmptyAndTrailingCommaBatches() {
        byte[] empty = "{\"segments\":[]}".getBytes(StandardCharsets.UTF_8);
        byte[] trailingComma = "{\"segments\":[\"one\",]}".getBytes(StandardCharsets.UTF_8);

        assertThrows(NativePromptFormatException.class,
                () -> NativePromptJsonReader.readSegments(
                        new ByteArrayInputStream(empty), empty.length));
        assertThrows(NativePromptFormatException.class,
                () -> NativePromptJsonReader.readSegments(
                        new ByteArrayInputStream(trailingComma), trailingComma.length));
    }

    @Test
    void readsDesanitizeModelResponseIntoNativeMemory() throws Exception {
        byte[] body = "{\"secureContext\":\"v2.k.n.ciphertext\","
                .concat("\"modelResponse\":\"The answer is 😀\"}")
                .getBytes(StandardCharsets.UTF_8);

        try (NativePromptJsonReader.NativeDesanitizeRequest request =
                NativePromptJsonReader.readDesanitize(
                        new ByteArrayInputStream(body), body.length)) {
            assertEquals("The answer is 😀",
                    new NativeUtf8CharSequence(request.response()).toString());
            assertEquals("v2.k.n.ciphertext", request.secureContext());
        }
    }

    @Test
    void readsBatchDesanitizeResponsesIntoNativeMemory() throws Exception {
        byte[] body = "{\"modelResponses\":[\"one\",\"two 😀\"],"
                .concat("\"secureContext\":\"v2.k.n.ciphertext\"}")
                .getBytes(StandardCharsets.UTF_8);

        try (NativePromptJsonReader.NativeBatchDesanitizeRequest request =
                NativePromptJsonReader.readBatchDesanitize(
                        new ByteArrayInputStream(body), body.length)) {
            assertEquals(List.of("one", "two 😀"),
                    request.responses().segments().stream()
                            .map(segment -> new NativeUtf8CharSequence(segment).toString())
                            .toList());
        }
    }
}
