package org.opentron.backend.orchestration;

import org.opentron.backend.llm.ModelClient;
import org.opentron.backend.llm.anthropic.AnthropicClient;
import org.opentron.backend.pricing.PricingService;
import org.opentron.backend.util.CloudModelService;
import org.opentron.backend.util.OllamaCliService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;

/**
 * ConditionalRouter - Routes requests based on complexity and domain
 *
 * Orchestration flow:
 * 1. ComplexityClassifier triages the request (fast local model)
 * 2. Complexity selects a cloud tier or local specialist model
 * 3. Track tokens and costs for all paths
 *
 * Model Selection Strategy:
 * - Anthropic: SIMPLE -> Haiku 5.5, COMPLEX -> Sonnet 5.5, EXTREME -> Fable 5.2
 * - Local code work: SIMPLE -> Qwen2.5 Coder 7B, COMPLEX/EXTREME -> DeepSeek R1 8B
 * - Local knowledge work: Gemma 3 4B
 * - Local triage: Qwen3 4B
 * - Anthropic requests use AnthropicClient
 *
 * Fable is reserved for rare, unusually difficult investigations and anomalies;
 * ordinary complex tasks use Sonnet.
 */
public class ConditionalRouter {
    
    private static final Logger logger = LoggerFactory.getLogger(ConditionalRouter.class);
    private static final String LOCAL_KNOWLEDGE_MODEL = "gemma3:4b";
    private static final String LOCAL_CODE_MODEL = "qwen2.5-coder:7b";
    private static final String LOCAL_COMPLEX_CODE_MODEL = "deepseek-r1:8b";
    
    private final ComplexityClassifierNode classifier;
    private final OllamaCliService ollamaService;
    private final CloudModelService cloudModelService;
    private final PricingService pricingService;
    private final String anthropicApiKey;
    private final boolean enableClaude;
    
    public ConditionalRouter(OllamaCliService ollamaService, CloudModelService cloudModelService, String anthropicApiKey) {
        this(ollamaService, cloudModelService, anthropicApiKey, null);
    }

    public ConditionalRouter(OllamaCliService ollamaService, CloudModelService cloudModelService, String anthropicApiKey, PricingService pricingService) {
        this.classifier = new ComplexityClassifierNode(ollamaService);
        this.ollamaService = ollamaService;
        this.cloudModelService = cloudModelService;
        this.pricingService = pricingService;
        this.anthropicApiKey = anthropicApiKey;
        this.enableClaude = anthropicApiKey != null && !anthropicApiKey.isBlank();
        
        if (enableClaude) {
            logger.info("ConditionalRouter initialized with Claude support (Anthropic API key present)");
            logger.info("  - SIMPLE -> claude-haiku-5-5, COMPLEX -> claude-sonnet-5-5, EXTREME -> claude-fable-5-2");
        } else {
            logger.info("ConditionalRouter initialized WITHOUT Claude (Anthropic API key missing - will use Ollama fallback)");
        }
        if (cloudModelService != null) {
            logger.info("ConditionalRouter initialized with CloudModelService support");
        }
    }
    
    /**
     * Route a request to the appropriate model client based on complexity and domain.
     * IMPORTANT: Selects the RIGHT client based on model type (Claude, Gemini, or Ollama)
     * 
     * @param userQuery The user's request
     * @param systemPrompt The system prompt/context
     * @param domain The specialist domain (backend, frontend, qa, devops, knowledge)
     * @param selectedModel The pre-selected model (may be overridden if Anthropic)
     * @param apiKeys Map of API keys from request (anthropic, google, etc.)
     * @return A RoutingDecision containing the selected ModelClient and metadata
     */
    public RoutingDecision route(String userQuery, String systemPrompt, String domain, String selectedModel, Map<String, String> apiKeys) {
        long routingStart = System.currentTimeMillis();

        try {
            if (cloudModelService != null && !cloudModelService.isInternetAllowed()) {
                logger.info("External internet disabled; routing request to local Ollama");
                return routeLocally(userQuery, domain, selectedModel, "External internet disabled", routingStart);
            }

            boolean hasAnthropicKey = enableClaude || hasAnthropicKey(apiKeys);
            boolean hasGoogleKey = apiKeys != null && apiKeys.containsKey("google")
                    && apiKeys.get("google") != null && !apiKeys.get("google").isBlank();
            if (!hasGoogleKey && cloudModelService != null) {
                hasGoogleKey = cloudModelService.hasApiKey("google", apiKeys);
            }
            boolean selectedModelIsClaude = selectedModel != null && selectedModel.startsWith("claude-");
            boolean selectedModelIsGemini = selectedModel != null && selectedModel.startsWith("gemini-");

            if (selectedModelIsClaude && !hasAnthropicKey) {
                return routeLocally(userQuery, domain, selectedModel, "Anthropic API key unavailable", routingStart);
            }
            if (selectedModelIsGemini && !hasGoogleKey) {
                return routeLocally(userQuery, domain, selectedModel, "Google API key unavailable", routingStart);
            }

            if (!hasAnthropicKey && !selectedModelIsGemini) {
                return routeLocally(userQuery, domain, selectedModel, "No selected cloud provider is available", routingStart);
            }

            ComplexityClassifierNode.ComplexityClassification classification = classify(userQuery);
            if (classification == null) {
                logger.warn("Classifier returned no result; using a safe model fallback");
                if (hasAnthropicKey) {
                    String fallbackModel = "claude-sonnet-5-5";
                    return new RoutingDecision(
                            createAnthropicClient(fallbackModel, apiKeys),
                            "CLAUDE",
                            "UNKNOWN",
                            "Classifier error, fallback to " + fallbackModel,
                            domain,
                            fallbackModel,
                            System.currentTimeMillis() - routingStart
                    );
                }
                if (selectedModelIsGemini && hasGoogleKey) {
                    return new RoutingDecision(
                            createCloudClient(selectedModel, apiKeys),
                            "GEMINI",
                            "UNKNOWN",
                            "Classifier error; using selected Gemini model",
                            domain,
                            selectedModel,
                            System.currentTimeMillis() - routingStart
                    );
                }
                return localDecision(domain, selectedModel, null, "Classifier error; using local fallback", routingStart);
            }

            if (hasAnthropicKey) {
                String modelToUse = selectClaudeModel(domain, classification);
                logger.info("[{}] {} request -> routing to {}", domain, classification.classification(), modelToUse);
                return new RoutingDecision(
                        createAnthropicClient(modelToUse, apiKeys),
                        "CLAUDE",
                        classification.classification(),
                        "Routed to " + modelToUse + ": " + classification.reasoning(),
                        domain,
                        modelToUse,
                        System.currentTimeMillis() - routingStart
                );
            }

            if (selectedModelIsGemini && hasGoogleKey) {
                return new RoutingDecision(
                        createCloudClient(selectedModel, apiKeys),
                        "GEMINI",
                        classification.classification(),
                        "Routed to " + selectedModel + ": " + classification.reasoning(),
                        domain,
                        selectedModel,
                        System.currentTimeMillis() - routingStart
                );
            }

            return localDecision(domain, selectedModel, classification,
                    "No selected cloud provider is available", routingStart);
        } catch (Exception e) {
            logger.error("Routing error, falling back to a safe model", e);
            if (resolveAnthropicKey(apiKeys) != null) {
                String fallbackModel = "claude-sonnet-5-5";
                return new RoutingDecision(
                        createAnthropicClient(fallbackModel, apiKeys),
                        "CLAUDE",
                        "UNKNOWN",
                        "Routing error, fallback to " + fallbackModel + ": " + e.getMessage(),
                        domain,
                        fallbackModel,
                        System.currentTimeMillis() - routingStart
                );
            }
            return localDecision(domain, selectedModel, null,
                    "Routing exception; using local fallback: " + e.getMessage(), routingStart);
        }
    }

    private ComplexityClassifierNode.ComplexityClassification classify(String userQuery) {
        try {
            return classifier.classify(userQuery).block();
        } catch (Exception e) {
            logger.warn("Complexity classification failed", e);
            return null;
        }
    }

    private RoutingDecision routeLocally(String userQuery, String domain, String selectedModel,
                                         String reason, long routingStart) {
        return localDecision(domain, selectedModel, classify(userQuery), reason, routingStart);
    }

    private RoutingDecision localDecision(String domain, String selectedModel,
                                          ComplexityClassifierNode.ComplexityClassification classification,
                                          String reason, long routingStart) {
        String model = selectLocalModel(domain, selectedModel, classification);
        String classificationName = classification == null ? "UNKNOWN" : classification.classification();
        String detail = classification == null ? reason : reason + ": " + classification.reasoning();
        return new RoutingDecision(
                createOllamaClient(model),
                "OLLAMA",
                classificationName,
                detail,
                domain,
                model,
                System.currentTimeMillis() - routingStart
        );
    }

    private String selectLocalModel(String domain, String selectedModel,
                                    ComplexityClassifierNode.ComplexityClassification classification) {
        String normalizedDomain = domain == null ? "" : domain.toLowerCase();
        if ("knowledge".equals(normalizedDomain)) {
            return LOCAL_KNOWLEDGE_MODEL;
        }
        if (List.of("backend", "frontend", "devops", "qa").contains(normalizedDomain)) {
            return classification != null && classification.isSimple()
                    ? LOCAL_CODE_MODEL
                    : LOCAL_COMPLEX_CODE_MODEL;
        }
        return isLocalModel(selectedModel) ? selectedModel : LOCAL_KNOWLEDGE_MODEL;
    }
    
    private boolean hasAnthropicKey(Map<String, String> apiKeys) {
        if (apiKeys != null) {
            for (String alias : List.of("anthropic", "claude", "claude_api_key", "ANTHROPIC_API_KEY")) {
                String key = apiKeys.get(alias);
                if (key != null && !key.isBlank()) {
                    return true;
                }
            }
        }

        if (cloudModelService != null) {
            return cloudModelService.hasApiKey("anthropic", apiKeys);
        }

        return false;
    }

    private String selectClaudeModel(String domain,
                                     ComplexityClassifierNode.ComplexityClassification classification) {
        String model = classification.isExtreme()
                ? "claude-fable-5-2"
                : classification.isComplex() ? "claude-sonnet-5-5" : "claude-haiku-5-5";
        logger.info("[{}] {} domain: Using {}", classification.classification(), domain, model);
        return model;
    }
    
    private ModelClient createOllamaClient(String model) {
        return new OllamaModelClient(ollamaService, model);
    }

    private boolean isLocalModel(String model) {
        return model != null && !model.startsWith("claude-") && !model.startsWith("gemini-")
                && !model.startsWith("gpt-") && !model.startsWith("openrouter/");
    }

    private ModelClient createAnthropicClient(String model, Map<String, String> apiKeys) {
        String resolvedApiKey = resolveAnthropicKey(apiKeys);
        if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
            logger.warn("Anthropic key unavailable for {}, falling back to Ollama", model);
            return createOllamaClient(LOCAL_KNOWLEDGE_MODEL);
        }

        return new AnthropicClient(model, resolvedApiKey);
    }
    
    private ModelClient createCloudClient(String model, Map<String, String> apiKeys) {
        if (cloudModelService != null) {
            return new CloudModelClient(cloudModelService, pricingService, model, apiKeys);
        }

        logger.warn("CloudModelService unavailable for {}, falling back to Ollama", model);
        return createOllamaClient(LOCAL_KNOWLEDGE_MODEL);
    }

    private String resolveAnthropicKey(Map<String, String> apiKeys) {
        if (apiKeys != null) {
            for (String alias : List.of("anthropic", "claude", "claude_api_key", "ANTHROPIC_API_KEY")) {
                if (apiKeys.containsKey(alias)) {
                    String key = apiKeys.get(alias);
                    if (key != null && !key.isBlank()) {
                        return key;
                    }
                    // Caller explicitly communicated no key for this alias (e.g. the
                    // user removed it in Settings). Respect that instead of silently
                    // falling back to a cached/environment-derived key.
                    return null;
                }
            }
        }

        if (anthropicApiKey != null && !anthropicApiKey.isBlank()) {
            return anthropicApiKey;
        }

        if (cloudModelService != null) {
            return cloudModelService.resolveApiKey("anthropic", apiKeys);
        }

        return null;
    }
    
    /**
     * RoutingDecision - encapsulates the routing result
     */
    public static class RoutingDecision {
        public final ModelClient client;
        public final String router;                  // "CLAUDE" or "OLLAMA"
        public final String classification;          // "SIMPLE", "COMPLEX", "UNKNOWN"
        public final String reason;                  // Human-readable reason
        public final String domain;                  // Specialist domain
        public final String selectedModel;           // Model name
        public final long classificationTimeMs;      // How long routing took
        
        public RoutingDecision(ModelClient client, String router, String classification,
                               String reason, String domain, String selectedModel, long classificationTimeMs) {
            this.client = client;
            this.router = router;
            this.classification = classification;
            this.reason = reason;
            this.domain = domain;
            this.selectedModel = selectedModel;
            this.classificationTimeMs = classificationTimeMs;
        }
        
        public boolean isClaude() {
            return "CLAUDE".equals(router);
        }
        
        public boolean isOllama() {
            return "OLLAMA".equals(router);
        }
        
        @Override
        public String toString() {
            return String.format("[%s] %s (%s) -> %s (%dms)",
                    domain, classification, reason, selectedModel, classificationTimeMs);
        }
    }
}
