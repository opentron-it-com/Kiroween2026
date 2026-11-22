package org.opentron.backend.services;

import org.springframework.stereotype.Service;
import org.opentron.backend.util.CloudModelService;
import org.opentron.backend.util.OllamaCliService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModelSelectorService - Intelligently selects the best model for each task
 * 
 * STRATEGY: CLOUD FIRST (when API keys available) - don't wait for Ollama to crash
 * Local only as fallback when no cloud keys
 */
@Service
public class ModelSelectorService {
    
    private final OllamaCliService ollamaService;
    private final CloudModelService cloudModelService;
    private static final Logger logger = LoggerFactory.getLogger(ModelSelectorService.class);
    private final Map<String, String> modelCache = new ConcurrentHashMap<>();
    private static final List<String> GEMINI_MODELS = List.of(
            "gemini-4-carbon", "gemini-4-argon", "gemini-4-neon", "gemini-4-hydrogen");
    private static final List<String> ANTHROPIC_MODELS = List.of(
            "claude-haiku-5-5", "claude-sonnet-5-5", "claude-fable-5-2");
    private long lastCacheUpdate = 0;
    private Map<String, String> lastApiKeyOverrides = null;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    public ModelSelectorService(OllamaCliService ollamaService, CloudModelService cloudModelService) {
        this.ollamaService = ollamaService;
        this.cloudModelService = cloudModelService;
        loadAvailableModels();
    }

    public String selectBestModel(String agentType) {
        return selectBestModel(agentType, null);
    }

    public String selectBestModel(String agentType, Map<String, String> apiKeyOverrides) {
        refreshModelCacheIfNeeded(apiKeyOverrides);
        
        logger.info("Selecting model for: {}", agentType);
        
        switch (agentType.toLowerCase()) {
            case "backend":
                return selectBackendModel();
            case "frontend":
                return selectFrontendModel();
            case "qa":
                return selectQAModel();
            case "devops":
                return selectDevOpsModel();
            case "knowledge":
                return selectKnowledgeModel();
            default:
                return selectGeneralModel();
        }
    }

    private String selectBackendModel() {
        if (hasModel("claude-sonnet-5-5")) {
            return "claude-sonnet-5-5";
        }
        String gemini = selectGeminiModel("gemini-4-carbon", "gemini-4-argon", "gemini-4-neon", "gemini-4-hydrogen");
        if (gemini != null) return gemini;
        return "qwen2.5-coder:7b";
    }

    private String selectFrontendModel() {
        if (hasModel("claude-haiku-5-5")) return "claude-haiku-5-5";
        String gemini = selectGeminiModel("gemini-4-hydrogen", "gemini-4-neon", "gemini-4-argon", "gemini-4-carbon");
        if (gemini != null) return gemini;
        return "qwen2.5-coder:7b";
    }

    private String selectQAModel() {
        if (hasModel("claude-haiku-5-5")) return "claude-haiku-5-5";
        String gemini = selectGeminiModel("gemini-4-hydrogen", "gemini-4-neon", "gemini-4-argon", "gemini-4-carbon");
        if (gemini != null) return gemini;
        return "qwen2.5-coder:7b";
    }

    private String selectDevOpsModel() {
        if (hasModel("claude-sonnet-5-5")) return "claude-sonnet-5-5";
        String gemini = selectGeminiModel("gemini-4-argon", "gemini-4-carbon", "gemini-4-neon", "gemini-4-hydrogen");
        if (gemini != null) return gemini;
        return "qwen2.5-coder:7b";
    }

    private String selectKnowledgeModel() {
        if (hasModel("claude-sonnet-5-5")) return "claude-sonnet-5-5";
        String gemini = selectGeminiModel("gemini-4-neon", "gemini-4-hydrogen", "gemini-4-argon", "gemini-4-carbon");
        if (gemini != null) return gemini;
        return "gemma3:4b";
    }

    private String selectGeneralModel() {
        if (hasModel("claude-sonnet-5-5")) return "claude-sonnet-5-5";
        String gemini = selectGeminiModel("gemini-4-neon", "gemini-4-hydrogen", "gemini-4-argon", "gemini-4-carbon");
        if (gemini != null) return gemini;
        return "gemma3:4b";
    }

    private String selectGeminiModel(String... preferredModels) {
        for (String model : preferredModels) {
            if (hasModel(model)) return model;
        }
        return null;
    }

    private boolean hasModel(String modelName) {
        return modelCache.containsKey(modelName);
    }

    private void loadAvailableModels() {
        loadAvailableModels(null);
    }

    private void loadAvailableModels(Map<String, String> apiKeyOverrides) {
        logger.info("Loading available models with overrides={}", apiKeyOverrides);
        try {
            modelCache.clear();

            // Load local models
            List<String> localModels = ollamaService.listModels().block();
            if (localModels != null) {
                for (String model : localModels) {
                    if (model != null && !model.isBlank()) {
                        modelCache.put(model.trim(), "available");
                    }
                }
            }

            if (!cloudModelService.isInternetAllowed()) {
                lastCacheUpdate = System.currentTimeMillis();
                lastApiKeyOverrides = apiKeyOverrides == null ? null : new HashMap<>(apiKeyOverrides);
                logger.info("External internet disabled; using local Ollama models only");
                return;
            }

            // Load cloud models
            List<String> cloudModels = null;
            try {
                if (cloudModelService != null) {
                    var cloudModelsMono = cloudModelService.listModels(apiKeyOverrides);
                    if (cloudModelsMono != null) {
                        cloudModels = cloudModelsMono.block();
                    }
                }
            } catch (Exception e) {
                logger.warn("Unable to load cloud models with overrides {}", apiKeyOverrides, e);
            }

            if (cloudModels != null) {
                for (String model : cloudModels) {
                    if (model != null && !model.isBlank()) {
                        modelCache.put(model.trim(), "available");
                    }
                }
            }

            addKnownCloudCandidates(apiKeyOverrides);

            lastCacheUpdate = System.currentTimeMillis();
            lastApiKeyOverrides = apiKeyOverrides == null ? null : new HashMap<>(apiKeyOverrides);
            logger.info("Loaded {} models: {}", modelCache.size(), modelCache.keySet());
        } catch (Exception e) {
            logger.error("Error loading models", e);
            modelCache.clear();
            modelCache.put("gemma3:4b", "available");
        }
    }

    private void addKnownCloudCandidates(Map<String, String> apiKeyOverrides) {
        boolean hasAnthropic = cloudModelService.hasApiKey("anthropic", apiKeyOverrides);
        boolean hasGoogle = cloudModelService.hasApiKey("google", apiKeyOverrides);
        boolean hasOpenRouter = cloudModelService.hasApiKey("openrouter", apiKeyOverrides);

        // Google models - add if key present, remove if absent
        if (hasGoogle) {
            if (GEMINI_MODELS.stream().noneMatch(modelCache::containsKey)) {
                modelCache.put("gemini-4-hydrogen", "available");
            }
        } else {
            GEMINI_MODELS.forEach(modelCache::remove);
        }

        // Anthropic models - add if key present, remove if absent.
        if (hasAnthropic) {
            ANTHROPIC_MODELS.forEach(model -> modelCache.putIfAbsent(model, "available"));
        } else {
            ANTHROPIC_MODELS.forEach(modelCache::remove);
        }

        // OpenRouter models - add if key present, remove if absent
        if (hasOpenRouter) {
            modelCache.putIfAbsent("openrouter/auto", "available");
        } else {
            modelCache.remove("openrouter/auto");
        }
    }

    private void refreshModelCacheIfNeeded(Map<String, String> apiKeyOverrides) {
        long now = System.currentTimeMillis();
        boolean overridesChanged = !Objects.equals(lastApiKeyOverrides, apiKeyOverrides == null ? null : new HashMap<>(apiKeyOverrides));
        if (overridesChanged || now - lastCacheUpdate > CACHE_TTL_MS) {
            logger.debug("Refreshing models because cache expired or API-key overrides changed");
            loadAvailableModels(apiKeyOverrides);
        }
    }

    public Map<String, Object> getModelSelectorStatus() {
        refreshModelCacheIfNeeded(null);
        return Map.of(
            "available_models", modelCache.keySet(),
            "model_count", modelCache.size(),
            "backend_model", selectBackendModel(),
            "frontend_model", selectFrontendModel(),
            "qa_model", selectQAModel(),
            "devops_model", selectDevOpsModel(),
            "knowledge_model", selectKnowledgeModel()
        );
    }
}