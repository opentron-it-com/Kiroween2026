package org.opentron.backend.security.confidential.gateway.api;

final class NativePromptFormatException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    NativePromptFormatException() {
        super("Request body is invalid.");
    }
}
