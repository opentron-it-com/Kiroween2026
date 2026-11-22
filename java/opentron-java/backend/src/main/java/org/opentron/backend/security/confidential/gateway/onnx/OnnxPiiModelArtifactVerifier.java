package org.opentron.backend.security.confidential.gateway.onnx;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

import org.opentron.backend.security.confidential.ConfidentialSegment;

public final class OnnxPiiModelArtifactVerifier {
    private static final int SCAN_CHUNK_BYTES = 1024 * 1024;

    private OnnxPiiModelArtifactVerifier() {
    }

    public static VerifiedModel verify(Path modelPath, String expectedSha256) {
        Objects.requireNonNull(modelPath, "modelPath");
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Expected ONNX model SHA-256 must be 64 hex characters.");
        }

        Path normalizedPath = modelPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedPath)) {
            throw new IllegalStateException("Configured ONNX model is missing or not a regular file.");
        }

        try {
            long fileSize = Files.size(normalizedPath);
            if (fileSize == 0) {
                throw new IllegalStateException("Configured ONNX model is empty.");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileChannel file = FileChannel.open(normalizedPath, StandardOpenOption.READ);
                    ConfidentialSegment chunk =
                            ConfidentialSegment.allocate(SCAN_CHUNK_BYTES)) {
                long fileOffset = 0;
                while (fileOffset < fileSize) {
                    int remaining = (int) Math.min(SCAN_CHUNK_BYTES, fileSize - fileOffset);
                    int chunkOffset = 0;
                    while (chunkOffset < remaining) {
                        int bytesRead = chunk.readFrom(
                                file,
                                fileOffset + chunkOffset,
                                chunkOffset,
                                remaining - chunkOffset);
                        if (bytesRead <= 0) {
                            throw new IOException("Model file ended while computing its digest.");
                        }
                        chunkOffset += bytesRead;
                    }
                    chunk.updateDigest(digest, 0, remaining);
                    chunk.zeroize();
                    fileOffset += remaining;
                }
            }

            byte[] actualDigest = digest.digest();
            byte[] expectedDigest = HexFormat.of().parseHex(expectedSha256);
            try {
                if (!MessageDigest.isEqual(actualDigest, expectedDigest)) {
                    throw new IllegalStateException(
                            "Configured ONNX model SHA-256 does not match the pinned digest.");
                }
            } finally {
                java.util.Arrays.fill(actualDigest, (byte) 0);
                java.util.Arrays.fill(expectedDigest, (byte) 0);
            }
            return new VerifiedModel(
                    normalizedPath, expectedSha256.toLowerCase(Locale.ROOT), fileSize);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read configured ONNX model.", exception);
        }
    }

    public record VerifiedModel(Path path, String sha256, long sizeBytes) {
        public VerifiedModel {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(sha256, "sha256");
            if (sizeBytes < 1) {
                throw new IllegalArgumentException("Verified ONNX model must not be empty.");
            }
        }
    }
}
