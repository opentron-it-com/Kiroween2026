package org.opentron.backend.agents;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class SpecializedAgent {
    protected String name;
    protected List<String> skills = new ArrayList<>();
    protected long lastExecutedTime = 0;
    protected AgentLLMBridge llmBridge;

    private static final Logger log = LoggerFactory.getLogger(SpecializedAgent.class);

    public SpecializedAgent(AgentLLMBridge llmBridge) {
        this.llmBridge = llmBridge;
    }

    public abstract Object process(AgentMessage msg);

    protected List<String> asStringList(Object value) {
        if (value instanceof List<?> values) {
            List<String> result = new ArrayList<>();
            for (Object item : values) {
                if (item != null) result.add(String.valueOf(item));
            }
            return result;
        }
        return List.of();
    }

    protected Map<String, Object> handleEmptyResponse(Map<String, Object> result, String agentName) {
        if (result == null) return result;
        String response = result.getOrDefault("response", "").toString().trim();
        
        if (response.isEmpty()) {
            boolean isError = "error".equals(result.get("status"));
            if (isError) {
                String errorMsg = String.valueOf(result.getOrDefault("error", "Unknown error"));
                result.put("response", agentName + " agent request failed: " + errorMsg);
                log.error("{} agent: request failed - {}", agentName, errorMsg);
            } else {
                Object stopReasonObj = result.get("stop_reason");
                boolean hitMaxTokens = stopReasonObj != null && "max_tokens".equals(String.valueOf(stopReasonObj));
                if (hitMaxTokens) {
                    result.put("response", agentName + " agent hit its token limit before producing a visible answer "
                            + "(stop_reason=max_tokens). Try increasing the output token budget for this agent.");
                    log.warn("{} agent: hit max_tokens with zero visible output - budget likely too low for this request", agentName);
                } else {
                    result.put("response",
                            "No relevant " + agentName + " tasks identified for this request.");
                    Object tokensObj = result.get("tokens_used");
                    long tokensUsed = tokensObj instanceof Number ? ((Number) tokensObj).longValue() : 0;
                    log.debug("{} agent: empty response handled - {} tokens used", agentName, tokensUsed);
                }
            }
        }
        return result;
    }

    protected boolean requestNeedsWebResearch(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("search the web") || lower.contains("search online")
                || lower.contains("research online") || lower.contains("latest news")
                || lower.contains("current events") || lower.contains("look up online")
                || lower.contains("find sources") || lower.contains("browse the web")
                || lower.contains("fetch this url") || lower.contains("fetch this page")
                || lower.contains("what's happening") || lower.contains("recent developments");
    }

    protected Map<String, Object> invokeScopedLLM(String role, String task, String context,
                                                   List<String> focus, List<String> ignore,
                                                   List<String> constraints, String agentKey,
                                                   StreamingCoordinatorCallback callback) {
        return invokeScopedLLM(role, task, context, focus, ignore, constraints, agentKey, agentKey, callback);
    }

    protected Map<String, Object> invokeScopedLLM(String role, String task, String context,
                                                   List<String> focus, List<String> ignore,
                                                   List<String> constraints, String agentKey,
                                                   String streamAgentKey,
                                                   StreamingCoordinatorCallback callback) {

        String modelName = llmBridge != null ? llmBridge.getModel() : "unknown";
        String baseSystemPrompt = AgentSystemPrompts.getSystemPrompt(agentKey);
        String systemPrompt = AgentSystemPrompts.enhanceWithAgentContext(baseSystemPrompt, agentKey, focus, constraints);

        boolean wantsWebResearch = requestNeedsWebResearch(task) || requestNeedsWebResearch(context)
                || "knowledge".equals(agentKey);
        if (wantsWebResearch) {
            systemPrompt = systemPrompt + "\n\n" + AgentPromptConstants.INTERNET_SEARCH_PROMPT;
        }

        String userQuestion = "Task: " + task
                + "\n\nRelevant context:\n" + context
                + "\n\nRespond with a helpful specialist answer.";
        final StringBuilder acc = new StringBuilder();

        Map<String, Object> result = llmBridge.queryLLMStream(systemPrompt, userQuestion, AgentPromptConstants.SPECIALIST_MAX_TOKENS,
                chunk -> {
                    acc.append(chunk);
                    if (callback != null) callback.onAgentChunk(streamAgentKey, chunk, modelName);
                },
                (resolvedModel, reason) -> {
                    if (callback != null) callback.onAgentModelResolved(streamAgentKey, resolvedModel);
                },
                step -> {
                    if (callback != null) callback.onAgentStep(streamAgentKey, step);
                });
        if (result != null
                && (!result.containsKey("response")
                    || String.valueOf(result.get("response")).isBlank())) {
            result.put("response", acc.toString());
        }
        result.put("agent", streamAgentKey);
        if (!result.containsKey("model") || result.get("model") == null || String.valueOf(result.get("model")).isBlank()) {
            result.put("model", modelName);
        }
        return result;
    }

    public Map<String, Object> getStatus() {
        return Map.of("name", name, "skills", skills, "last_executed_ms", lastExecutedTime);
    }
}
