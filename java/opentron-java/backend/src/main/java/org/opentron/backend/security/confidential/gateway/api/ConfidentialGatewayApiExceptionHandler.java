package org.opentron.backend.security.confidential.gateway.api;

import org.opentron.backend.security.confidential.gateway.SecureContextException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ConfidentialGatewayController.class)
@ConditionalOnProperty(name = "confidential.gateway.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConfidentialGatewayApiExceptionHandler {
    private static final Logger logger =
            LoggerFactory.getLogger(ConfidentialGatewayApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<GatewayApiError> handleInvalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request validation failed.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<GatewayApiError> handleUnreadableRequest() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body is invalid.");
    }

    @ExceptionHandler(NativePromptFormatException.class)
    public ResponseEntity<GatewayApiError> handleInvalidNativePrompt() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body is invalid.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<GatewayApiError> handleInvalidArgument() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request parameters are invalid.");
    }

    @ExceptionHandler(SecureContextException.class)
    public ResponseEntity<GatewayApiError> handleInvalidContext() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_CONTEXT",
                "Secure context is invalid, expired, or unavailable.");
    }

    @ExceptionHandler(GatewayQuotaExceededException.class)
    public ResponseEntity<GatewayApiError> handleQuotaExceeded() {
        return error(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED",
                "Tenant request or text quota has been exceeded.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GatewayApiError> handleUnexpectedFailure(Exception exception) {
        logger.error("Confidential gateway request failed: {}",
                exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The confidential gateway request could not be completed.");
    }

    private static ResponseEntity<GatewayApiError> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new GatewayApiError(code, message));
    }
}
