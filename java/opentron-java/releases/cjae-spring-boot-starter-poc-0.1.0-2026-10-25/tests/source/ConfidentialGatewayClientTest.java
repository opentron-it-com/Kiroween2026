package org.opentron.backend.security.confidential.gateway.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** HTTP contract tests for the standalone Gateway client artifact. */
class ConfidentialGatewayClientTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void sanitizeAndDesanitizeUseTypedAuthenticatedRequests() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> sanitizePayload = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            if ("/v1/confidential/sanitize".equals(path)) {
                sanitizePayload.set(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, """
                        {"sanitizedText":"Contact <OJE_EMAIL_token>","secureContext":"v1.key.AA.BB","detectorVersion":"test","expiresAt":"2026-01-01T00:10:00Z"}
                        """);
            } else {
                respond(exchange, 200, "{\"desanitizedText\":\"Contact ada@example.com\"}");
            }
        });

        try {
            ConfidentialGatewayClient client = client(server);
            var sanitized = client.sanitize("Contact ada@example.com");

            assertEquals("Contact <OJE_EMAIL_token>", sanitized.sanitizedText());
            assertEquals("test", sanitized.detectorVersion());
            assertEquals("2026-01-01T00:10:00Z", sanitized.expiresAt().toString());
            assertEquals("Bearer " + TOKEN, authorization.get());
            assertTrue(sanitizePayload.get().contains("Contact ada@example.com"));

            assertEquals("Contact ada@example.com",
                    client.desanitize(sanitized.sanitizedText(), sanitized.secureContext()));
            assertEquals("Bearer " + TOKEN, authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exposesSafeTypedErrorWithoutServerMessage() throws Exception {
        HttpServer server = startServer(exchange -> respond(
                exchange, 400,
                "{\"code\":\"INVALID_CONTEXT\",\"message\":\"raw sensitive server detail\"}"));

        try {
            GatewayClientException exception = assertThrows(
                    GatewayClientException.class,
                    () -> client(server).desanitize("response", "v1.key.AA.BB"));

            assertEquals(400, exception.statusCode());
            assertEquals("INVALID_CONTEXT", exception.errorCode());
            assertFalse(exception.getMessage().contains("raw sensitive server detail"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void completionCompositionSendsOnlySanitizedPromptToModel() throws Exception {
        HttpServer server = startServer(exchange -> {
            if ("/v1/confidential/sanitize".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, """
                        {"sanitizedText":"Write to <OJE_EMAIL_token>","secureContext":"v1.key.AA.BB","detectorVersion":"test","expiresAt":"2026-01-01T00:10:00Z"}
                        """);
            } else {
                respond(exchange, 200, "{\"desanitizedText\":\"Sent to ada@example.com\"}");
            }
        });

        try {
            AtomicReference<String> modelInput = new AtomicReference<>();
            var completion = new ConfidentialCompletionClient(
                    client(server),
                    prompt -> {
                        modelInput.set(prompt);
                        return "Sent to <OJE_EMAIL_token>";
                    });

            assertEquals("Sent to ada@example.com",
                    completion.complete("Write to ada@example.com"));
            assertEquals("Write to <OJE_EMAIL_token>", modelInput.get());
            assertFalse(modelInput.get().contains("ada@example.com"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void batchClientSanitizesAndRestoresSegmentsUsingOneContext() throws Exception {
        HttpServer server = startServer(exchange -> {
            if ("/v1/confidential/sanitize/batch".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, """
                        {"sanitizedSegments":["Email <OJE_EMAIL_a>","Phone <OJE_PHONE_NUMBER_b>"],"secureContext":"v1.key.AA.BB","detectorVersion":"test","expiresAt":"2026-01-01T00:10:00Z"}
                        """);
            } else {
                respond(exchange, 200,
                        "{\"desanitizedResponses\":[\"Email ada@example.com\",\"Phone 212-555-0199\"]}");
            }
        });

        try {
            var client = client(server);
            var sanitized = client.sanitizeSegments(
                    List.of("Email ada@example.com", "Phone 212-555-0199"));

            assertEquals(2, sanitized.sanitizedSegments().size());
            assertFalse(sanitized.sanitizedSegments().get(0).contains("ada@example.com"));
            assertEquals(
                    List.of("Email ada@example.com", "Phone 212-555-0199"),
                    client.desanitizeSegments(sanitized.sanitizedSegments(), sanitized.secureContext()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void conversationCompositionSanitizesEveryRoleBeforeModelAndRestoresResponse() throws Exception {
        AtomicReference<String> sanitizePayload = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/v1/confidential/sanitize/batch".equals(path)) {
                sanitizePayload.set(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, """
                        {"sanitizedSegments":["Remember <OJE_EMAIL_a>","Write to <OJE_EMAIL_a>"],"secureContext":"v1.key.AA.BB","detectorVersion":"test","expiresAt":"2026-01-01T00:10:00Z"}
                        """);
            } else {
                respond(exchange, 200,
                        "{\"desanitizedResponses\":[\"Sent to ada@example.com\"]}");
            }
        });

        try {
            AtomicReference<List<ConfidentialConversationClient.Message>> modelInput =
                    new AtomicReference<>();
            var conversationClient = new ConfidentialConversationClient(client(server));
            List<ConfidentialConversationClient.Message> response = conversationClient.complete(
                    List.of(
                            new ConfidentialConversationClient.Message(
                                    "assistant", "Remember ada@example.com"),
                            new ConfidentialConversationClient.Message(
                                    "user", "Write to ada@example.com")),
                    messages -> {
                        modelInput.set(messages);
                        return List.of(new ConfidentialConversationClient.Message(
                                "assistant", "Sent to <OJE_EMAIL_a>"));
                    });

            assertTrue(sanitizePayload.get().contains("ada@example.com"));
            assertEquals(List.of("Remember <OJE_EMAIL_a>", "Write to <OJE_EMAIL_a>"),
                    modelInput.get().stream()
                            .map(ConfidentialConversationClient.Message::content)
                            .toList());
            assertFalse(modelInput.get().toString().contains("ada@example.com"));
            assertEquals(
                    List.of(new ConfidentialConversationClient.Message(
                            "assistant", "Sent to ada@example.com")),
                    response);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requiresHttpsOutsideLoopback() {
        assertThrows(IllegalArgumentException.class, () -> new ConfidentialGatewayClient(
                URI.create("http://gateway.example.com"),
                TOKEN,
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Duration.ofSeconds(5)));
    }

    @Test
    void rejectsWeakCredentialsAndTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> new ConfidentialGatewayClient(
                URI.create("http://localhost:8000"),
                "short",
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new ConfidentialGatewayClient(
                URI.create("http://localhost:8000"),
                TOKEN,
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Duration.ZERO));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "weather", "recipe", "gardening", "travel itinerary", "book review",
        "public schedule", "meeting agenda", "product description",
        "general knowledge", "tutorial", "arithmetic", "sports score",
        "movie summary", "language translation", "documentation",
        "public announcement", "restaurant menu", "historical fact",
        "packing list", "code example"
    })
    void sendsApprovedKeywordExamplesToGateway(String keyword) throws Exception {
        HttpServer server = keywordPolicyServer();
        try {
            var result = client(server).sanitize("Please help with " + keyword + ".");
            assertEquals("approved", result.sanitizedText());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "password", "passcode", "secret key", "api key", "access token",
        "private key", "social security number", "ssn", "credit card",
        "bank account", "medical record", "diagnosis", "patient",
        "date of birth", "passport number", "driver license",
        "insurance policy", "biometric", "confidential", "restricted"
    })
    void returnsTypedRejectionForGatewayRejectedKeywordExamples(String keyword) throws Exception {
        HttpServer server = keywordPolicyServer();
        try {
            GatewayClientException exception = assertThrows(
                    GatewayClientException.class,
                    () -> client(server).sanitize("Please process " + keyword + "."));

            assertEquals(422, exception.statusCode());
            assertEquals("POLICY_REJECTED", exception.errorCode());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer keywordPolicyServer() throws IOException {
        List<String> rejectedKeywords = List.of(
                "password", "passcode", "secret key", "api key", "access token",
                "private key", "social security number", "ssn", "credit card",
                "bank account", "medical record", "diagnosis", "patient",
                "date of birth", "passport number", "driver license",
                "insurance policy", "biometric", "confidential", "restricted");
        ObjectMapper mapper = new ObjectMapper();
        return startServer(exchange -> {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String text = mapper.readTree(requestBody).path("text").asText();
            boolean rejected = rejectedKeywords.stream()
                    .anyMatch(keyword -> text.toLowerCase(Locale.ROOT).contains(keyword));
            if (rejected) {
                respond(exchange, 422,
                        "{\"code\":\"POLICY_REJECTED\",\"message\":\"fixture policy match\"}");
            } else {
                respond(exchange, 200, """
                        {"sanitizedText":"approved","secureContext":"v1.key.AA.BB","detectorVersion":"test","expiresAt":"2026-01-01T00:10:00Z"}
                        """);
            }
        });
    }

    private static ConfidentialGatewayClient client(HttpServer server) {
        return new ConfidentialGatewayClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                TOKEN,
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Duration.ofSeconds(5));
    }

    private static HttpServer startServer(
            com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange,
            int status,
            String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
