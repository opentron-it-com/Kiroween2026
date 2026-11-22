package org.opentron.backend.security.confidential.gateway.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConfidentialGatewayApiKeyInterceptorTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsConfiguredBearerToken() throws Exception {
        ConfidentialGatewayApiKeyInterceptor interceptor =
                new ConfidentialGatewayApiKeyInterceptor(authenticator(TOKEN));
        MockHttpServletRequest request = request();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
        assertEquals("tenant-test",
                GatewayRequestContext.requirePrincipal(request).tenantId());
    }

    @Test
    void rejectsMissingBearerToken() throws Exception {
        ConfidentialGatewayApiKeyInterceptor interceptor =
                new ConfidentialGatewayApiKeyInterceptor(authenticator(TOKEN));
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader(HttpHeaders.WWW_AUTHENTICATE));
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    void rejectsIncorrectBearerToken() throws Exception {
        ConfidentialGatewayApiKeyInterceptor interceptor =
                new ConfidentialGatewayApiKeyInterceptor(authenticator(TOKEN));
        MockHttpServletRequest request = request();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + "f".repeat(32));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void rejectsShortConfiguredToken() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new LocalStaticBearerCredentialAuthenticator(
                        "too-short", "tenant-test"));
    }

    @Test
    void requiresScopeForOperation() throws Exception {
        GatewayCredentialAuthenticator authenticator = token -> java.util.Optional.of(
                new GatewayPrincipal("subject-1", "tenant-test", Set.of("gateway:sanitize")));
        ConfidentialGatewayApiKeyInterceptor interceptor =
                new ConfidentialGatewayApiKeyInterceptor(authenticator);
        MockHttpServletRequest request = request();
        request.setRequestURI("/v1/confidential/desanitize");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void hidesUnknownConfidentialRoutes() throws Exception {
        ConfidentialGatewayApiKeyInterceptor interceptor =
                new ConfidentialGatewayApiKeyInterceptor(authenticator(TOKEN));
        MockHttpServletRequest request = request();
        request.setRequestURI("/v1/confidential/admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(404, response.getStatus());
    }

    private static GatewayCredentialAuthenticator authenticator(String token) {
        return new LocalStaticBearerCredentialAuthenticator(token, "tenant-test");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/confidential/sanitize");
        request.setRequestURI("/v1/confidential/sanitize");
        return request;
    }
}
