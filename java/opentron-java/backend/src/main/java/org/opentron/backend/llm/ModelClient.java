package org.opentron.backend.llm;

import java.util.Map;
import java.util.function.Consumer;

/**
 * ModelClient abstraction interface
 * Decouples agent orchestration from specific model implementations (Ollama, Anthropic, Google, etc.)
 * All implementations must handle streaming callbacks and token tracking transparently.
 */
public interface ModelClient {
    
    /**
     * Query the model with a single synchronous call.
     * 
     * @param systemPrompt System context/role definition
     * @param userQuestion User's input message
     * @param maxTokens Maximum output tokens
     * @return Response map containing: status, response, tokens_used, model, cost_usd
     */
    Map<String, Object> query(String systemPrompt, String userQuestion, int maxTokens);
    
    /**
     * Query the model with streaming callback support.
     * Blocks until complete, calling onChunk for each partial response.
     * 
     * @param systemPrompt System context/role definition
     * @param userQuestion User's input message
     * @param maxTokens Maximum output tokens
     * @param onChunk Callback invoked for each chunk (may be called multiple times)
     * @return Response map with final result including tokens_used and cost_usd
     */
    Map<String, Object> queryStream(String systemPrompt, String userQuestion, int maxTokens, Consumer<String> onChunk);
    
    /**
     * Get the underlying model name/identifier
     */
    String getModelName();
    
    /**
     * Get the provider name (e.g., "ollama", "anthropic", "google")
     */
    String getProvider();
    
    /**
     * Health check: verify the client can reach the underlying service
     */
    boolean isHealthy();
}
