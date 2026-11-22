package org.opentron.backend.orchestration;

import org.opentron.backend.llm.ModelClient;
import org.opentron.backend.pricing.PricingService;
import org.opentron.backend.util.CloudModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CloudModelClient - Adapter for CloudModelService to fit the ModelClient abstraction.
 */
public class CloudModelClient implements ModelClient {

    private static final Logger logger = LoggerFactory.getLogger(CloudModelClient.class);

    private final CloudModelService cloudModelService;
    private final PricingService pricingService;
    private final String model;
    private final Map<String, String> apiKeys;

    public CloudModelClient(CloudModelService cloudModelService, String model, Map<String, String> apiKeys) {
        this(cloudModelService, null, model, apiKeys);
    }

    public CloudModelClient(CloudModelService cloudModelService, PricingService pricingService, String model, Map<String, String> apiKeys) {
        this.cloudModelService = cloudModelService;
        this.pricingService = pricingService;
        this.model = model;
        this.apiKeys = apiKeys == null ? Map.of() : new HashMap<>(apiKeys);
    }

    @Override
    public Map<String, Object> query(String systemPrompt, String userQuestion, int maxTokens) {
        return queryStream(systemPrompt, userQuestion, maxTokens, chunk -> {});
    }

    @Override
    public Map<String, Object> queryStream(String systemPrompt, String userQuestion, int maxTokens, Consumer<String> onChunk) {
        long startTime = System.currentTimeMillis();

        try {
            logger.info("CloudModelClient querying {} with userQuestion length: {}", model, userQuestion != null ? userQuestion.length() : 0);

            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt != null ? systemPrompt : ""),
                    Map.of("role", "user", "content", userQuestion != null ? userQuestion : "")
            );

            Mono<Map<String, Object>> responseMono = cloudModelService.callCloudModel(model, messages, apiKeys);
            Map<String, Object> response = responseMono.block();
            long elapsedMs = System.currentTimeMillis() - startTime;

            if (response == null) {
                return errorResponse("CloudModelService returned null", elapsedMs);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return errorResponse("Empty choices from cloud response", elapsedMs);
            }

            Map<String, Object> choice = choices.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            String content = message != null ? (String) message.get("content") : "";

            if (content == null || content.isBlank()) {
                return errorResponse("Empty content from cloud response", elapsedMs);
            }

            onChunk.accept(content);

            String provider = model.startsWith("claude-") ? "anthropic" : model.startsWith("gemini-") ? "google" : "cloud";

            @SuppressWarnings("unchecked")
            Map<String, Object> usage = response.get("usage") instanceof Map
                ? (Map<String, Object>) response.get("usage")
                : Map.of();
            long inputTokens = usage.get("prompt_tokens") instanceof Number n ? n.longValue() : 0;
            long outputTokens = usage.get("completion_tokens") instanceof Number n ? n.longValue() : 0;
            long totalTokens = usage.get("total_tokens") instanceof Number n ? n.longValue() : inputTokens + outputTokens;

            double costUsd = pricingService != null
                ? pricingService.costUsd(model, inputTokens, outputTokens).orElse(0.0)
                : 0.0;

            Map<String, Object> result = new HashMap<>();
            result.put("status", "completed");
            result.put("response", content);
            result.put("model", model);
            result.put("provider", provider);
            result.put("elapsed_ms", elapsedMs);
            result.put("cost_usd", costUsd);
            result.put("tokens_used", totalTokens);
            result.put("input_tokens", inputTokens);
            result.put("output_tokens", outputTokens);

            return result;
        } catch (Exception e) {
            logger.error("CloudModelClient error querying {}", model, e);
            return errorResponse("Cloud model error: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProvider() {
        if (model != null && model.startsWith("claude-")) {
            return "anthropic";
        }
        if (model != null && model.startsWith("gemini-")) {
            return "google";
        }
        return "cloud";
    }

    @Override
    public boolean isHealthy() {
        try {
            return cloudModelService.hasApiKey(getProvider(), apiKeys);
        } catch (Exception e) {
            logger.warn("CloudModelClient health check failed for {}: {}", model, e.getMessage());
            return false;
        }
    }

    private Map<String, Object> errorResponse(String error, long elapsedMs) {
        return Map.of(
                "status", "error",
                "error", error,
                "model", model,
                "provider", getProvider(),
                "elapsed_ms", elapsedMs,
                "cost_usd", 0.0
        );
    }
}
