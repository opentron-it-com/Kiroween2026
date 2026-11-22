package org.opentron.backend.security.confidential.gateway.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import javax.crypto.KeyGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentron.backend.security.confidential.gateway.AesGcmSecureContextCodec;
import org.opentron.backend.security.confidential.gateway.ConfidentialGatewayService;
import org.opentron.backend.security.confidential.gateway.PrototypeRuleBasedPiiDetector;
import org.opentron.backend.security.confidential.gateway.SecureContextKey;
import org.opentron.backend.security.confidential.gateway.SecureContextKeyProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

class ConfidentialGatewayControllerTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var service = buildGatewayService(clock);
        var controller = new ConfidentialGatewayController(
                service,
                new FixedWindowGatewayQuotaService(
                        100, 10_000_000, 100, Duration.ofMinutes(1), clock));
        var authenticator =
                new LocalStaticBearerCredentialAuthenticator(TOKEN, "tenant-test");
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ConfidentialGatewayApiExceptionHandler())
                .addInterceptors(new ConfidentialGatewayApiKeyInterceptor(authenticator))
                .build();
    }

    private static ConfidentialGatewayService buildGatewayService() throws Exception {
        return buildGatewayService(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private static ConfidentialGatewayService buildGatewayService(Clock clock) throws Exception {
        var generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecureContextKey key = new SecureContextKey("test-key", generator.generateKey());
        SecureContextKeyProvider keyProvider = new SecureContextKeyProvider() {
            @Override
            public SecureContextKey currentKey() {
                return key;
            }

            @Override
            public Optional<SecureContextKey> findKey(String id) {
                return key.id().equals(id) ? Optional.of(key) : Optional.empty();
            }
        };
        var codec = new AesGcmSecureContextCodec(
                keyProvider, new ObjectMapper(), new SecureRandom(), clock);
        return new ConfidentialGatewayService(
                new PrototypeRuleBasedPiiDetector(),
                codec,
                Duration.ofMinutes(10),
                new SecureRandom(),
                clock);
    }

    @Test
    void sanitizeAndDesanitizeRequireBearerAuthAndDoNotCacheResponses() throws Exception {
        var sanitizeResponse = mockMvc.perform(post("/v1/confidential/sanitize")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Contact ada@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.sanitizedText", not(containsString("ada@example.com"))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var json = new ObjectMapper().readTree(sanitizeResponse);
        String sanitizedText = json.get("sanitizedText").asText();
        String secureContext = json.get("secureContext").asText();

        mockMvc.perform(post("/v1/confidential/desanitize")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(
                                new DesanitizeRequest(sanitizedText, secureContext))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.desanitizedText").value("Contact ada@example.com"));
    }

    @Test
    void streamsRestoredJsonWithoutChangingEscapingOrResponseShape() throws Exception {
        String original = "Contact \"ada@example.com\"\n😀";
        String sanitizeBody = mockMvc.perform(post("/v1/confidential/sanitize")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(
                                java.util.Map.of("text", original))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var sanitized = new ObjectMapper().readTree(sanitizeBody);
        String request = new ObjectMapper().writeValueAsString(
                java.util.Map.of(
                        "modelResponse", sanitized.path("sanitizedText").asText(),
                        "secureContext", sanitized.path("secureContext").asText()));

        String response = mockMvc.perform(post("/v1/confidential/desanitize")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertEquals(original,
                new ObjectMapper().readTree(response).path("desanitizedText").asText());
    }

    @Test
    void streamsSanitizedJsonWithoutChangingResponseShape() throws Exception {
        String original = "Contact \"ada@example.com\"\n😀";
        String response = mockMvc.perform(post("/v1/confidential/sanitize")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(
                                java.util.Map.of("text", original))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var json = new ObjectMapper().readTree(response);
        assertFalse(json.path("sanitizedText").asText().contains("ada@example.com"));
        assertTrue(json.path("secureContext").asText().startsWith("v3."));
        assertEquals("prototype-rules-2", json.path("detectorVersion").asText());
    }

    @Test
    void rejectsRequestsWithoutBearerCredentials() throws Exception {
        mockMvc.perform(post("/v1/confidential/sanitize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Contact ada@example.com\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsInvalidRequestBodiesWithoutEchoingInput() throws Exception {
        mockMvc.perform(post("/v1/confidential/sanitize")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", not(containsString("ada@example.com"))));

        mockMvc.perform(post("/v1/confidential/sanitize")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"valid after malformed input\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void mapsMalformedJsonToSafeClientError() throws Exception {
        mockMvc.perform(post("/v1/confidential/sanitize")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is invalid."));
    }

    @Test
    void returns429WhenTenantQuotaIsExceeded() throws Exception {
        var quota = new FixedWindowGatewayQuotaService(
                1,
                100,
                10,
                Duration.ofMinutes(1),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        var controller = new ConfidentialGatewayController(
                buildGatewayService(), quota);
        var authenticator =
                new LocalStaticBearerCredentialAuthenticator(TOKEN, "tenant-test");
        var quotaMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ConfidentialGatewayApiExceptionHandler())
                .addInterceptors(new ConfidentialGatewayApiKeyInterceptor(authenticator))
                .build();

        quotaMvc.perform(post("/v1/confidential/sanitize")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"first\"}"))
                .andExpect(status().isOk());
        quotaMvc.perform(post("/v1/confidential/sanitize")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"second\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
    }

    @Test
    void tenantIdentityComesFromAuthenticatedCredentialAndContextsCannotCrossTenants()
            throws Exception {
        GatewayCredentialAuthenticator authenticator = credential -> switch (credential) {
            case "tenant-one-token-0123456789abcdef" -> Optional.of(new GatewayPrincipal(
                    "subject-one",
                    "tenant-one",
                    java.util.Set.of("gateway:sanitize", "gateway:desanitize")));
            case "tenant-two-token-0123456789abcdef" -> Optional.of(new GatewayPrincipal(
                    "subject-two",
                    "tenant-two",
                    java.util.Set.of("gateway:sanitize", "gateway:desanitize")));
            default -> Optional.empty();
        };
        var controller = new ConfidentialGatewayController(
                buildGatewayService(),
                new FixedWindowGatewayQuotaService(
                        100,
                        10_000_000,
                        100,
                        Duration.ofMinutes(1),
                        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)));
        var multiTenantMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ConfidentialGatewayApiExceptionHandler())
                .addInterceptors(new ConfidentialGatewayApiKeyInterceptor(authenticator))
                .build();
        String response = multiTenantMvc.perform(post("/v1/confidential/sanitize")
                        .header("Authorization", "Bearer tenant-one-token-0123456789abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Contact ada@example.com\",\"tenantId\":\"tenant-two\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = new ObjectMapper().readTree(response);

        multiTenantMvc.perform(post("/v1/confidential/desanitize")
                        .header("Authorization", "Bearer tenant-two-token-0123456789abcdef")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(
                                new DesanitizeRequest(
                                        json.path("sanitizedText").asText(),
                                        json.path("secureContext").asText()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONTEXT"));
    }

    @Test
    void batchEndpointsSanitizeAndRestoreSegments() throws Exception {
        var sanitizeResponse = mockMvc.perform(post("/v1/confidential/sanitize/batch")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segments\":[\"Email ada@example.com\",\"Phone 212-555-0199\"]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.sanitizedSegments.length()").value(2))
                .andExpect(jsonPath("$.sanitizedSegments[0]", not(containsString("ada@example.com"))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var json = new ObjectMapper().readTree(sanitizeResponse);
        var request = new BatchDesanitizeRequest(
                List.of(
                        json.get("sanitizedSegments").get(0).asText(),
                        json.get("sanitizedSegments").get(1).asText()),
                json.get("secureContext").asText());

        mockMvc.perform(post("/v1/confidential/desanitize/batch")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desanitizedResponses[0]").value("Email ada@example.com"))
                .andExpect(jsonPath("$.desanitizedResponses[1]").value("Phone 212-555-0199"));
    }
}
