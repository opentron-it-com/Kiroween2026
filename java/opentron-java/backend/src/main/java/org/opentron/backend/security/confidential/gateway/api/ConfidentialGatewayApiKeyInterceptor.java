package org.opentron.backend.security.confidential.gateway.api;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

public final class ConfidentialGatewayApiKeyInterceptor implements HandlerInterceptor {
    private static final String BEARER_PREFIX = "Bearer ";

    private final GatewayCredentialAuthenticator credentialAuthenticator;

    public ConfidentialGatewayApiKeyInterceptor(
            GatewayCredentialAuthenticator credentialAuthenticator) {
        this.credentialAuthenticator = credentialAuthenticator;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws IOException {
        String requiredScope = requiredScope(request);
        if (requiredScope == null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            return false;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)
                || authorization.length() == BEARER_PREFIX.length()
                || authorization.substring(BEARER_PREFIX.length()).contains(" ")) {
            return reject(response);
        }

        Optional<GatewayPrincipal> principal = credentialAuthenticator.authenticate(
                authorization.substring(BEARER_PREFIX.length()));
        if (principal.isEmpty()) {
            return reject(response);
        }
        if (!principal.get().hasScope(requiredScope)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
            response.getWriter().write(
                    "{\"code\":\"FORBIDDEN\",\"message\":\"Credential is not authorized for this operation.\"}");
            return false;
        }
        request.setAttribute(GatewayRequestContext.PRINCIPAL_ATTRIBUTE, principal.get());
        return true;
    }

    private static String requiredScope(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return switch (path) {
            case "/v1/confidential/sanitize", "/v1/confidential/sanitize/batch" ->
                "gateway:sanitize";
            case "/v1/confidential/desanitize", "/v1/confidential/desanitize/batch" ->
                "gateway:desanitize";
            default -> null;
        };
    }

    private static boolean reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        response.getWriter().write(
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"Valid bearer credentials are required.\"}");
        return false;
    }
}
