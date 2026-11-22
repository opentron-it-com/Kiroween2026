package org.opentron.backend.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPreloaderTest {

    private HttpServer ollama;
    private AtomicBoolean pullRequested;
    private List<JsonNode> warmupRequests;
    private ModelPreloader preloader;

    @BeforeEach
    void startOllamaStub() throws IOException {
        pullRequested = new AtomicBoolean();
        warmupRequests = new CopyOnWriteArrayList<>();
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/tags", exchange ->
            respond(exchange, 200, """
                {"models":[
                  {"name":"installed:latest"},
                  {"name":"qwen3:4b"},
                  {"name":"gemma3:4b"},
                  {"name":"qwen2.5-coder:7b"},
                  {"name":"deepseek-r1:8b"}
                ]}
                """));
        ollama.createContext("/api/pull", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            pullRequested.set(request.equals("{\"model\":\"missing:latest\",\"stream\":false}"));
            respond(exchange, 200, "{\"status\":\"success\"}");
        });
        ollama.createContext("/api/generate", exchange -> {
            warmupRequests.add(new ObjectMapper().readTree(exchange.getRequestBody()));
            respond(exchange, 200, "{\"response\":\"ready\",\"done\":true}");
        });
        ollama.start();
        preloader = new ModelPreloader(
            new ObjectMapper(), "http://127.0.0.1:" + ollama.getAddress().getPort());
    }

    @AfterEach
    void stopOllamaStub() {
        ollama.stop(0);
    }

    @Test
    void doesNotPullModelAlreadyInstalled() throws Exception {
        assertTrue(preloader.ensureModelAvailable("installed:latest"));
        assertFalse(pullRequested.get());
    }

    @Test
    void pullsModelWhenItIsMissing() throws Exception {
        assertTrue(preloader.ensureModelAvailable("missing:latest"));
        assertTrue(pullRequested.get());
    }

    @Test
    void preloadsAllRoleModelsWithEvictionFriendlyKeepAlive() {
        preloader.run();

        assertEquals(Set.of("qwen3:4b", "gemma3:4b", "qwen2.5-coder:7b", "deepseek-r1:8b"),
            warmupRequests.stream().map(request -> request.path("model").asText()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(4, warmupRequests.size());
        assertTrue(warmupRequests.stream().allMatch(request -> "5m".equals(request.path("keep_alive").asText())));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
