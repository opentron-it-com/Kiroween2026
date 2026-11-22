package org.opentron.cjae.worker;

public record AwsNitroConfidentialBenchmarkCall(
        int plaintextBytes,
        int encryptedContextBytes,
        long encryptionNanos,
        long decryptionNanos,
        boolean roundTripVerified) {
}
