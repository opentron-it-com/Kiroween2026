package org.opentron.backend.agents;

import org.opentron.backend.llm.ModelClient;
import org.opentron.backend.orchestration.ConditionalRouter;
import org.opentron.backend.pricing.PricingService;
import org.opentron.backend.util.CloudModelService;
import org.opentron.backend.services.WebSearchService;
import org.opentron.backend.util.OllamaCliService;
import org.opentron.backend.util.HuggingFaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * AgentLLMBridge v2 - Enhanced with ConditionalRouter for hybrid Ollama/Claude orchestration
 *
 * NEW CAPABILITIES:
 * - Conditional routing: SIMPLE requests to Ollama (free), COMPLEX to Claude (optimized)
 * - Automatic model selection based on agent domain
 * - Token cost tracking for all model invocations
 * - Prompt caching support when Claude routes through AnthropicClient
 * - Fallback chain: Claude → Google → Ollama
 *
 * Virtual threads handle blocking I/O; only fails if all services are down.
 */
public class AgentLLMBridge {

    @SuppressWarnings("unused")
    private final OllamaCliService ollamaService;
    @SuppressWarnings("unused")
    private final HuggingFaceService huggingFaceService;
    @SuppressWarnings("unused")
    private final CloudModelService cloudModelService;
    private final String model;
    private final String domain;  // For conditional routing decisions
    @SuppressWarnings("unused")
    private final boolean useHF;
    private final Map<String, String> apiKeyOverrides;
    private final WebSearchService webSearchService;
    private final ConditionalRouter conditionalRouter;
    private static final Logger logger = LoggerFactory.getLogger(AgentLLMBridge.class);

    /**
     * Keywords/phrases that signal the user wants a live web search (current events,
     * lookups, prices, etc.) rather than a question about their own notes/emails/
     * calendar. Only the "knowledge" domain agent consults this - see
     * {@link #shouldSearchWeb(String)}.
     */
    private static final Pattern SEARCH_INTENT_PATTERN = Pattern.compile(
            "\\b(search|look\\s*up|lookup|google|latest|current|today'?s?|recent(ly)?|" +
            "news|breaking|what'?s\\s+happening|what\\s+is|who\\s+is|when\\s+(did|is|was|does)|" +
            "how\\s+much\\s+(does|is|do)|price\\s+of|weather|score\\s+of|stock\\s+price)\\b",
            Pattern.CASE_INSENSITIVE);

    public AgentLLMBridge(OllamaCliService ollamaService, HuggingFaceService huggingFaceService, String model) {
        this(ollamaService, huggingFaceService, null, model, null, null, null, null);
    }

    public AgentLLMBridge(OllamaCliService ollamaService, HuggingFaceService huggingFaceService, CloudModelService cloudModelService,
                          String model, Map<String, String> apiKeyOverrides) {
        this(ollamaService, huggingFaceService, cloudModelService, model, apiKeyOverrides, null, null, null);
    }

    public AgentLLMBridge(OllamaCliService ollamaService, HuggingFaceService huggingFaceService, CloudModelService cloudModelService,
                          String model, Map<String, String> apiKeyOverrides, WebSearchService webSearchService) {
        this(ollamaService, huggingFaceService, cloudModelService, model, apiKeyOverrides, webSearchService, null, null);
    }

    public AgentLLMBridge(OllamaCliService ollamaService, HuggingFaceService huggingFaceService, CloudModelService cloudModelService,
                          String model, Map<String, String> apiKeyOverrides, WebSearchService webSearchService, String domain) {
        this(ollamaService, huggingFaceService, cloudModelService, model, apiKeyOverrides, webSearchService, domain, null);
    }

    public AgentLLMBridge(OllamaCliService ollamaService, HuggingFaceService huggingFaceService, CloudModelService cloudModelService,
                          String model, Map<String, String> apiKeyOverrides, WebSearchService webSearchService, String domain,
                          PricingService pricingService) {
        this.ollamaService = ollamaService;
        this.huggingFaceService = huggingFaceService;
        this.cloudModelService = cloudModelService;
        this.model = model != null ? model : "gemma3:4b";
        this.domain = domain != null ? domain : "general";
        this.useHF = System.getenv("HF_MODE") != null &&
                    ("local".equalsIgnoreCase(System.getenv("HF_MODE")) ||
                     "api".equalsIgnoreCase(System.getenv("HF_MODE")));
        this.apiKeyOverrides = apiKeyOverrides == null ? Collections.emptyMap() : apiKeyOverrides;
        this.webSearchService = webSearchService;

        // Initialize conditional router for hybrid orchestration
        String anthropicKey = extractAnthropicKey(apiKeyOverrides);
        this.conditionalRouter = new ConditionalRouter(ollamaService, cloudModelService, anthropicKey, pricingService);

        logger.info("Initialized AgentLLMBridge for model: {} (domain: {}) with conditional routing", this.model, this.domain);
    }

    /**
     * Query LLM using ConditionalRouter for intelligent routing
     */
    public Map<String, Object> queryLLM(String systemPrompt, String userQuestion, int maxTokens) {
        return queryLLMInternal(systemPrompt, userQuestion, maxTokens, chunk -> {}, null, null);
    }

    /**
     * Stream-aware variant with conditional routing
     */
    public Map<String, Object> queryLLMStream(String systemPrompt, String userQuestion, int maxTokens, Consumer<String> onChunk) {
        return queryLLMInternal(systemPrompt, userQuestion, maxTokens, onChunk, null, null);
    }

    /**
     * Stream-aware variant that also reports live progress:
     *  - onModelResolved(model, reason): fired once the ConditionalRouter has picked
     *    the actual model to use, BEFORE the request goes out. This is the only
     *    place the real model is known - the caller's own "model" field (this
     *    bridge's static per-domain default) is provisional until this fires.
     *  - onStep(message): short, human-readable progress lines (routing, model
     *    resolved, querying, response received) suitable for direct UI display.
     * Both are optional (pass null to skip).
     */
    public Map<String, Object> queryLLMStream(String systemPrompt, String userQuestion, int maxTokens,
                                               Consumer<String> onChunk,
                                               BiConsumer<String, String> onModelResolved,
                                               Consumer<String> onStep) {
        return queryLLMInternal(systemPrompt, userQuestion, maxTokens, onChunk, onModelResolved, onStep);
    }

    /**
     * Whether this bridge should attempt a live web search before answering.
     * Scoped to the "knowledge" domain agent only (the personal knowledge assistant
     * that KNOWLEDGE_PROMPT describes as having "real-time web search via Tavily"),
     * and only when the question itself looks like it needs current/external
     * information - so routine questions about the user's own notes, emails, or
     * calendar don't trigger an unnecessary Tavily call.
     */
    private boolean shouldSearchWeb(String userQuestion) {
        if (webSearchService == null) return false;
        if (!"knowledge".equalsIgnoreCase(domain)) return false;
        if (userQuestion == null || userQuestion.isBlank()) return false;
        return SEARCH_INTENT_PATTERN.matcher(userQuestion).find();
    }

    private Map<String, Object> queryLLMInternal(String systemPrompt, String userQuestion, int maxTokens,
                                                   Consumer<String> onChunk,
                                                   BiConsumer<String, String> onModelResolved,
                                                   Consumer<String> onStep) {
        try {
            long routingStart = System.currentTimeMillis();

            logger.info("Querying with model: {}, domain: {}", model, domain);
            if (onStep != null) onStep.accept("Routing request…");

            // Prepend current server date/time
            if (systemPrompt == null) systemPrompt = "";

            // Augment system prompt with web search results if applicable
            String augmentedSystem = systemPrompt;
            try {
                if (shouldSearchWeb(userQuestion)) {
                    logger.info("[{}] Search intent detected, querying live web search: {}", domain, userQuestion);
                    if (onStep != null) onStep.accept("Searching the web…");
                    var results = webSearchService.search(userQuestion, 3);
                    if (results != null && !results.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("\n\nSearch results (top " + results.size() + "):\n");
                        for (var r : results) {
                            String title = r.getOrDefault("title", "");
                            String link = r.getOrDefault("link", "");
                            String snippet = r.getOrDefault("snippet", "");
                            if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
                            sb.append("- ").append(title).append(" (").append(link).append("): ").append(snippet).append("\n");
                        }
                        augmentedSystem = systemPrompt + sb.toString();
                        logger.info("[{}] Web search returned {} results", domain, results.size());
                    } else {
                        logger.info("[{}] Web search returned no results (check TAVILY_API_KEY / network policy)", domain);
                    }
                }
            } catch (Exception e) {
                logger.warn("WebSearch augmentation failed", e);
            }

            // USE CONDITIONAL ROUTER to determine which model to use
            ConditionalRouter.RoutingDecision decision = conditionalRouter.route(
                    userQuestion,
                    augmentedSystem,
                    domain,
                    model,
                    apiKeyOverrides
            );

            long routingElapsed = System.currentTimeMillis() - routingStart;
            logger.info("Routing decision: {}, routing took {}ms", decision, routingElapsed);

            // The actual model is only known now - report it immediately, before
            // the request goes out, so callers/UI aren't stuck showing whatever
            // provisional default they had before routing ran.
            if (onModelResolved != null) onModelResolved.accept(decision.selectedModel, decision.reason);
            if (onStep != null) {
                onStep.accept("Routed to " + decision.selectedModel
                        + (decision.reason != null && !decision.reason.isBlank() ? " — " + decision.reason : ""));
                onStep.accept("Querying " + decision.selectedModel + "…");
            }

            // Execute with selected model client
            ModelClient selectedClient = decision.client;
            Map<String, Object> response = new HashMap<>(selectedClient.queryStream(augmentedSystem, userQuestion, maxTokens, onChunk));

            if (onStep != null) {
                onStep.accept(buildResponseReceivedStep(response));
            }

            // Ensure the actual selected model is explicitly preserved for downstream consumers.
            response.put("model", decision.selectedModel);
            response.put("selected_model", decision.selectedModel);

            // Enrich response with routing metadata
            response.put("routing_decision", decision.router);
            response.put("classification", decision.classification);
            response.put("routing_reason", decision.reason);
            response.put("routing_time_ms", decision.classificationTimeMs);
            response.put("domain", domain);

            if (response.containsKey("status") && "completed".equals(response.get("status"))) {
                response = parseRecommendations(String.valueOf(response.getOrDefault("response", "")), response);
            }

            return response;

        } catch (Exception e) {
            logger.error("Error in queryLLM with conditional routing", e);
            return errorResponse("LLM routing failed: " + e.getMessage());
        }
    }

public String getModel() {
        return this.model;
    }

    /**
     * Renders a short, human-readable "response received" progress line from
     * a completed query's result map, e.g.:
     *   "Response received — 8,339 chars · 21,485 tokens · $0.10 · 1.6s"
     * Falls back gracefully when a field is missing (e.g. Ollama results have
     * no cost_usd/cache fields) rather than showing "null"/0 for everything.
     */
    private String buildResponseReceivedStep(Map<String, Object> response) {
        if (response == null) return "Response received.";
        if ("error".equals(response.get("status"))) {
            return "Request failed: " + response.getOrDefault("error", "unknown error");
        }
        String text = String.valueOf(response.getOrDefault("response", ""));
        int chars = text.equals("null") ? 0 : text.length();

        StringBuilder sb = new StringBuilder("Response received — ").append(String.format("%,d", chars)).append(" chars");

        Object tokens = response.get("tokens_used");
        if (tokens instanceof Number n) {
            sb.append(" · ").append(String.format("%,d", n.longValue())).append(" tokens");
        }

        Object cost = response.get("cost_usd");
        if (cost instanceof Number n && n.doubleValue() > 0) {
            sb.append(" · $").append(String.format("%.4f", n.doubleValue()));
        }

        Object elapsed = response.get("elapsed_ms");
        if (elapsed instanceof Number n) {
            double seconds = n.longValue() / 1000.0;
            sb.append(" · ").append(String.format("%.1fs", seconds));
        }

        return sb.toString();
    }

        private String extractAnthropicKey(Map<String, String> overrides) {
        if (overrides != null) {
            for (String alias : List.of("anthropic", "claude", "claude_api_key", "ANTHROPIC_API_KEY")) {
                if (overrides.containsKey(alias)) {
                    String key = overrides.get(alias);
                    if (key != null && !key.isBlank()) {
                        return key;
                    }
                    // Caller explicitly communicated no key for this alias (e.g. the
                    // user removed it in Settings). Respect that instead of silently
                    // falling back to a server-side environment variable.
                    return null;
                }
            }
        }
        String envKey = System.getenv("ANTHROPIC_API_KEY");
        return envKey != null && !envKey.isBlank() ? envKey : null;
    }

    private Map<String, Object> parseRecommendations(String content, Map<String, Object> response) {
        Map<String, Object> result = new HashMap<>(response);

        if (content == null || content.isBlank()) {
            result.put("recommendations", List.of());
            return result;
        }

        List<String> recommendations = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("^[-•*\\d+.]+\\s+.*")) {
                String rec = trimmed.replaceAll("^[-•*\\d+.]+\\s+", "").trim();
                if (!rec.isEmpty()) recommendations.add(rec);
            }
        }
        if (recommendations.isEmpty()) {
            for (String sentence : content.split("[.!?]+")) {
                String s = sentence.trim();
                if (s.length() > 10) recommendations.add(s);
            }
        }
        result.put("recommendations", recommendations);

        return result;
    }

    private Map<String, Object> errorResponse(String error) {
        return Map.of(
                "status", "error",
                "error", error,
                "recommendations", List.of(),
                "cost_usd", 0.0
        );
    }
}
