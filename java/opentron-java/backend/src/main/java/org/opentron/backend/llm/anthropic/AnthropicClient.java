package org.opentron.backend.llm.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentron.backend.llm.ModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/*** AnthropicClient - Fully compliant REST adapter for Anthropic Messages API
 */
public class AnthropicClient implements ModelClient {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicClient.class);
    private static final String API_BASE = "https://api.anthropic.com/v1";
    private static final List<String> API_VERSIONS = List.of("2023-06-01");
    private static final String PROMPT_CACHING_BETA_HEADER = "prompt-caching-2024-07-31";

    private static final String DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5";
    private static final int MAX_ANTHROPIC_TEXT_LENGTH = 120_000;

    private static final int FALLBACK_MAX_OUTPUT_TOKENS = 8192; // unrecognized model safety net
    private static final Map<String, Integer> MAX_OUTPUT_TOKENS = Map.of(
        "claude-fable-5-2", 128_000,
        "claude-sonnet-5-5", 128_000,
        "claude-haiku-5-5", 128_000
    );

    private final String model;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final Map<String, double[]> PRICING = Map.of(
        "claude-sonnet-5-5", new double[]{2.00, 10.00, 2.50, 0.10},
        "claude-fable-5-2",  new double[]{8.00, 40.00, 10.00, 0.20},
        "claude-haiku-5-5",  new double[]{0.10, 0.50, 0.125, 0.01}
    );

    public AnthropicClient(String model, String apiKey) {
        this.model = normalizeModel(model);
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Anthropic API key is required");
        }

        logger.info("Initialized AnthropicClient for model: {}", this.model);
    }

    private String normalizeModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return DEFAULT_ANTHROPIC_MODEL;
        }

        String trimmed = modelName.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("claude-")) {
            return trimmed;
        }

        return DEFAULT_ANTHROPIC_MODEL;
    }

    @Override
    public Map<String, Object> query(String systemPrompt, String userQuestion, int maxTokens) {
        return queryStream(systemPrompt, userQuestion, maxTokens, chunk -> {});
    }

    @Override
    public Map<String, Object> queryStream(String systemPrompt, String userQuestion, int maxTokens, Consumer<String> onChunk) {
        long startTime = System.currentTimeMillis();

        try {
            logger.info("AnthropicClient querying {} with systemPrompt length: {}, userQuestion length: {}",
                    model, systemPrompt != null ? systemPrompt.length() : 0, userQuestion != null ? userQuestion.length() : 0);

            String stableSystem = systemPrompt != null ? systemPrompt : "";
            String now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
            String userPayload = (userQuestion != null ? userQuestion : "")
                    + "\n\nCurrent server date/time: " + now;

            int cappedMaxTokens = Math.min(maxTokens, MAX_OUTPUT_TOKENS.getOrDefault(model, FALLBACK_MAX_OUTPUT_TOKENS));
            String safeSystemPrompt = truncateForAnthropic(stableSystem, MAX_ANTHROPIC_TEXT_LENGTH);
            String safeUserPayload = truncateForAnthropic(userPayload, MAX_ANTHROPIC_TEXT_LENGTH);
            String jsonBody = buildRequestPayload(model, safeSystemPrompt, safeUserPayload, cappedMaxTokens, true);
            logger.debug("Anthropic request summary: model={}, systemChars={}, userChars={}, maxTokens={}",
                    model, safeSystemPrompt.length(), safeUserPayload.length(), cappedMaxTokens);

            HttpResponse<InputStream> response = sendAnthropicStreamRequest(jsonBody);

            if (response.statusCode() != 200) {
                // Error happens before any streaming - elapsed-so-far is the right
                // measurement here (there's no generation time to account for).
                long errorElapsedMs = System.currentTimeMillis() - startTime;
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                logger.error("Anthropic API error: {} - {}", response.statusCode(), errorBody);
                return errorResponse("Anthropic API error: " + response.statusCode() + " - " + errorBody, errorElapsedMs);
            }

            AnthropicResponse.AnthropicUsage usage = new AnthropicResponse.AnthropicUsage();
            StringBuilder fullResponse = new StringBuilder();
            String[] stopReason = new String[1];

            try (InputStream inputStream = response.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String payload = line.substring(5).trim();
                        if ("[DONE]".equals(payload)) {
                            break;
                        }
                        if (!payload.isEmpty()) {
                            processStreamEvent(payload, fullResponse, onChunk, usage, stopReason);
                        }
                    }
                }
            }

            long elapsedMs = System.currentTimeMillis() - startTime;

            double cost = calculateCost(usage);

            logger.info("Anthropic response: {} chars, {} total tokens, cost: ${}, elapsed: {}ms",
                    fullResponse.length(), usage.getTotalTokens(), cost, elapsedMs);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "completed");
            result.put("response", fullResponse.toString());
            result.put("model", model);
            result.put("provider", "anthropic");
            result.put("tokens_used", usage.getTotalTokens());
            result.put("input_tokens", usage.input_tokens);
            result.put("output_tokens", usage.output_tokens);
            result.put("cache_creation_tokens", usage.cache_creation_input_tokens);
            result.put("cache_read_tokens", usage.cache_read_input_tokens);
            result.put("stop_reason", stopReason[0]);
            result.put("cost_usd", cost);
            result.put("elapsed_ms", elapsedMs);

            return result;

        } catch (Exception e) {
            logger.error("Error querying Anthropic API", e);
            return errorResponse("Anthropic error: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    static String truncateForAnthropic(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n[truncated by AnthropicClient]";
    }

    private String buildRequestPayload(String modelName, String systemPrompt, String userPayload, int maxTokens, boolean stream) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", modelName == null ? "" : modelName);
        payload.put("max_tokens", Math.max(1, maxTokens));
        payload.put("stream", stream);

        if (supportsEffortParameter(modelName)) {
            payload.put("output_config", Map.of("effort", "medium"));
        }

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            Map<String, Object> systemBlock = new LinkedHashMap<>();
            systemBlock.put("type", "text");
            systemBlock.put("text", systemPrompt);
            systemBlock.put("cache_control", Map.of("type", "ephemeral"));
            payload.put("system", List.of(systemBlock));
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", userPayload == null ? "" : userPayload);
        payload.put("messages", List.of(message));

        return objectMapper.writeValueAsString(payload);
    }

    private boolean supportsEffortParameter(String modelName) {
        return "claude-sonnet-5-5".equals(modelName);
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProvider() {
        return "anthropic";
    }

    @Override
    public boolean isHealthy() {
        try {
            HttpResponse<String> response = sendAnthropicRequestWithVersion(
                    "{\"model\":\"" + model + "\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":\"health check\"}]}",
                    "2023-06-01"
            );
            return response.statusCode() == 200;
        } catch (Exception e) {
            logger.warn("Anthropic health check failed: {}", e.getMessage());
            return false;
        }
    }

    private HttpResponse<InputStream> sendAnthropicStreamRequest(String jsonBody) throws Exception {
        return sendAnthropicStreamRequestWithVersion(jsonBody, API_VERSIONS.get(0));
    }

    private HttpResponse<String> sendAnthropicRequestWithVersion(String jsonBody, String apiVersion) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/messages"))
                .header("x-api-key", apiKey)
                .header("content-type", "application/json")
                .header("anthropic-version", apiVersion)
                .header("anthropic-beta", PROMPT_CACHING_BETA_HEADER)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<InputStream> sendAnthropicStreamRequestWithVersion(String jsonBody, String apiVersion) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/messages"))
                .header("x-api-key", apiKey)
                .header("content-type", "application/json")
                .header("anthropic-version", apiVersion)
                .header("anthropic-beta", PROMPT_CACHING_BETA_HEADER)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private double calculateCost(AnthropicResponse.AnthropicUsage usage) {
        double[] pricing = PRICING.getOrDefault(model, new double[]{3.00, 15.00, 3.75, 0.30});

        double inputCost = (usage.input_tokens / 1_000_000.0) * pricing[0];
        double outputCost = (usage.output_tokens / 1_000_000.0) * pricing[1];
        double cacheCost = (usage.cache_creation_input_tokens / 1_000_000.0) * pricing[2];
        double cacheReadCost = (usage.cache_read_input_tokens / 1_000_000.0) * pricing[3];

        return inputCost + outputCost + cacheCost + cacheReadCost;
    }

    private void processStreamEvent(String eventJson, StringBuilder fullResponse, Consumer<String> onChunk,
                                    AnthropicResponse.AnthropicUsage usage, String[] stopReason) {
        try {
            JsonNode root = objectMapper.readTree(eventJson);
            String eventType = root.path("type").asText();

            if ("message_start".equals(eventType)) {
                JsonNode messageUsage = root.path("message").path("usage");
                if (!messageUsage.isMissingNode()) {
                    usage.input_tokens = messageUsage.path("input_tokens").asInt(0);
                    usage.cache_creation_input_tokens = messageUsage.path("cache_creation_input_tokens").asInt(0);
                    usage.cache_read_input_tokens = messageUsage.path("cache_read_input_tokens").asInt(0);
                }
            } else if ("content_block_delta".equals(eventType)) {
                JsonNode delta = root.path("delta");
                if ("text_delta".equals(delta.path("type").asText())) {
                    String chunk = delta.path("text").asText("");
                    if (!chunk.isEmpty()) {
                        fullResponse.append(chunk);
                        onChunk.accept(chunk);
                    }
                }
            } else if ("message_delta".equals(eventType)) {
                JsonNode delta = root.path("delta");
                if (delta.has("stop_reason")) {
                    stopReason[0] = delta.path("stop_reason").asText();
                }
                JsonNode deltaUsage = root.path("usage");
                if (!deltaUsage.isMissingNode()) {
                    usage.output_tokens = deltaUsage.path("output_tokens").asInt(usage.output_tokens);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse Anthropic stream event: {}", e.getMessage());
        }
    }

    private Map<String, Object> errorResponse(String error, long elapsedMs) {
        return Map.of(
                "status", "error",
                "error", error,
                "model", model,
                "provider", "anthropic",
                "elapsed_ms", elapsedMs
        );
    }
}