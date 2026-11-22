package org.opentron.backend.security.confidential.gateway.aws;

public final class AwsNitroEnclaveException extends RuntimeException {
    public AwsNitroEnclaveException(String message) {
        super(message);
    }

    public AwsNitroEnclaveException(String message, Throwable cause) {
        super(message, cause);
    }
}
