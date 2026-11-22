package org.opentron.backend.security.confidential.gateway.sdk;

/** Safe, typed failure returned by the remote Gateway client. */
public final class GatewayClientException extends RuntimeException {
    private final int statusCode;
    private final String errorCode;

    public GatewayClientException(int statusCode, String errorCode) {
        super("Confidential Gateway request failed (" + errorCode + ").");
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public GatewayClientException(int statusCode, String errorCode, Throwable cause) {
        super("Confidential Gateway request failed (" + errorCode + ").", cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
