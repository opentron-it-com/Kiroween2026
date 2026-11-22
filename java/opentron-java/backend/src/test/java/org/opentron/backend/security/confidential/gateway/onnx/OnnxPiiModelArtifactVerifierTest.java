package org.opentron.backend.security.confidential.gateway.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnnxPiiModelArtifactVerifierTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesModelDigestWithoutLoadingWholeFileIntoHeap() throws Exception {
        Path model = temporaryDirectory.resolve("model.onnx");
        byte[] contents = new byte[OnnxPiiModelArtifactVerifierTest.CHUNK_BYTES + 19];
        for (int i = 0; i < contents.length; i++) {
            contents[i] = (byte) (i * 31);
        }
        Files.write(model, contents);
        String expectedDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(contents));

        var verified = OnnxPiiModelArtifactVerifier.verify(model, expectedDigest);

        assertEquals(model.toAbsolutePath().normalize(), verified.path());
        assertEquals(expectedDigest, verified.sha256());
        assertEquals(contents.length, verified.sizeBytes());
    }

    @Test
    void rejectsDigestMismatchAndEmptyFile() throws Exception {
        Path model = temporaryDirectory.resolve("model.onnx");
        Files.writeString(model, "not the pinned model");

        assertThrows(
                IllegalStateException.class,
                () -> OnnxPiiModelArtifactVerifier.verify(model, "0".repeat(64)));

        Files.write(model, new byte[0]);
        assertThrows(
                IllegalStateException.class,
                () -> OnnxPiiModelArtifactVerifier.verify(
                        model, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    }

    @Test
    void rejectsMalformedExpectedDigest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OnnxPiiModelArtifactVerifier.verify(
                        temporaryDirectory.resolve("model.onnx"), "not-a-digest"));
    }

    private static final int CHUNK_BYTES = 1024 * 1024;
}
