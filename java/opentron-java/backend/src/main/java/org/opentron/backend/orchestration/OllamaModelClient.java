package org.opentron.backend.orchestration;

import org.opentron.backend.llm.ModelClient;
import org.opentron.backend.util.OllamaCliService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/**
 * OllamaModelClient - ModelClient adapter for local Ollama instances
 * Wraps OllamaCliService to provide unified ModelClient interface
 */
public class OllamaModelClient implements ModelClient {
    
    private static final Logger logger = LoggerFactory.getLogger(OllamaModelClient.class);
    
    private final OllamaCliService ollamaService;
    private final String model;
    
    public OllamaModelClient(OllamaCliService ollamaService, String model) {
        this.ollamaService = ollamaService;
        this.model = model != null ? model : "gemma3:4b";
    }
    
    @Override
    public Map<String, Object> query(String systemPrompt, String userQuestion, int maxTokens) {
        return queryStream(systemPrompt, userQuestion, maxTokens, chunk -> {});
    }
    
    @Override
    public Map<String, Object> queryStream(String systemPrompt, String userQuestion, int maxTokens, Consumer<String> onChunk) {
        long startTime = System.currentTimeMillis();
        
        try {
            logger.info("OllamaModelClient querying {} with systemPrompt length: {}, userQuestion length: {}",
                    model, systemPrompt != null ? systemPrompt.length() : 0, userQuestion != null ? userQuestion.length() : 0);
            
            // Prepend current server timestamp
            String now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
            String augmentedSystem = (systemPrompt != null ? systemPrompt : "") 
                    + "\n\nCurrent server date/time: " + now;
            
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", augmentedSystem));
            messages.add(Map.of("role", "user", "content", userQuestion != null ? userQuestion : ""));
            
            Map<String, Object> response = ollamaService.chatCompletion(model, messages).block();
            long elapsedMs = System.currentTimeMillis() - startTime;
            
            if (response == null) {
                return errorResponse("Ollama returned null", elapsedMs);
            }
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return errorResponse("Empty choices in Ollama response", elapsedMs);
            }
            
            Map<String, Object> choice = choices.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            String content = message != null ? (String) message.get("content") : "";
            
            if (content == null || content.isBlank()) {
                return errorResponse("Empty content from Ollama", elapsedMs);
            }
            
            onChunk.accept(content);
            
            logger.info("Ollama {} responded: {} chars, {} tokens, elapsed: {}ms",
                    model, content.length(), response.get("usage"), elapsedMs);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "completed");
            result.put("response", content);
            result.put("model", model);
            result.put("provider", "ollama");
            result.put("cost_usd", 0.0);  // Local Ollama is free
            result.put("elapsed_ms", elapsedMs);
            
            // Ollama responses may include usage metrics
            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            if (usage != null) {
                result.put("tokens_used", usage.get("total_tokens"));
                result.put("input_tokens", usage.get("prompt_tokens"));
                result.put("output_tokens", usage.get("completion_tokens"));
            } else {
                result.put("tokens_used", 0);
            }
            
            return result;
            
        } catch (Exception e) {
            logger.error("Error querying Ollama {}", model, e);
            return errorResponse("Ollama error: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
    
    @Override
    public String getModelName() {
        return model;
    }
    
    @Override
    public String getProvider() {
        return "ollama";
    }
    
    @Override
    public boolean isHealthy() {
        try {
            List<String> models = ollamaService.listModels().block();
            return models != null && !models.isEmpty();
        } catch (Exception e) {
            logger.warn("Ollama health check failed: {}", e.getMessage());
            return false;
        }
    }
    
    private Map<String, Object> errorResponse(String error, long elapsedMs) {
        return Map.of(
                "status", "error",
                "error", error,
                "model", model,
                "provider", "ollama",
                "cost_usd", 0.0,
                "elapsed_ms", elapsedMs
        );
    }
}
