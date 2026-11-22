package org.opentron.backend.orchestration;

import org.junit.jupiter.api.Test;
import org.opentron.backend.util.CloudModelService;
import org.opentron.backend.util.OllamaCliService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConditionalRouterTest {

    @Test
    void routesSimpleLocalCodeToQwenCoder() {
        assertLocalModelForClassification("SIMPLE", "backend", "qwen2.5-coder:7b");
    }

    @Test
    void routesComplexLocalCodeToDeepSeekR1() {
        assertLocalModelForClassification("COMPLEX", "frontend", "deepseek-r1:8b");
    }

    @Test
    void routesLocalKnowledgeWorkToGemma() {
        assertLocalModelForClassification("SIMPLE", "knowledge", "gemma3:4b");
    }

    @Test
    void routesOrdinaryComplexAnthropicWorkToSonnet() {
        assertAnthropicModelForClassification("COMPLEX", "claude-sonnet-5-5");
    }

    @Test
    void routesSimpleAnthropicWorkToHaiku() {
        assertAnthropicModelForClassification("SIMPLE", "claude-haiku-5-5");
    }

    @Test
    void reservesFableForExtremeAnthropicWork() {
        assertAnthropicModelForClassification("EXTREME", "claude-fable-5-2");
    }

    @Test
    void resolvesAnthropicKeyFromCloudConfiguration() {
        OllamaCliService ollama = mock(OllamaCliService.class);
        CloudModelService cloud = mock(CloudModelService.class);
        when(cloud.isInternetAllowed()).thenReturn(true);
        when(cloud.hasApiKey(eq("anthropic"), anyMap())).thenReturn(true);
        when(cloud.resolveApiKey(eq("anthropic"), anyMap())).thenReturn("test-key");
        when(ollama.chatCompletion(eq("qwen3:4b"), anyList()))
                .thenReturn(Mono.just(classifierResponse("SIMPLE")));

        ConditionalRouter.RoutingDecision decision = new ConditionalRouter(ollama, cloud, null)
                .route("test request", "", "backend", "qwen2.5-coder:7b", Map.of());

        assertEquals("claude-haiku-5-5", decision.selectedModel);
        assertEquals("CLAUDE", decision.router);
    }

    private static void assertLocalModelForClassification(String classification, String domain, String expectedModel) {
        OllamaCliService ollama = mock(OllamaCliService.class);
        CloudModelService cloud = mock(CloudModelService.class);
        when(cloud.isInternetAllowed()).thenReturn(false);
        when(ollama.chatCompletion(eq("qwen3:4b"), anyList()))
                .thenReturn(Mono.just(classifierResponse(classification)));

        ConditionalRouter.RoutingDecision decision = new ConditionalRouter(ollama, cloud, null)
                .route("test request", "", domain, "qwen2.5-coder:7b", Map.of());

        assertEquals(expectedModel, decision.selectedModel);
        assertEquals("OLLAMA", decision.router);
    }

    private static void assertAnthropicModelForClassification(String classification, String expectedModel) {
        OllamaCliService ollama = mock(OllamaCliService.class);
        when(ollama.chatCompletion(eq("qwen3:4b"), anyList()))
                .thenReturn(Mono.just(classifierResponse(classification)));

        ConditionalRouter.RoutingDecision decision = new ConditionalRouter(ollama, null, "test-key")
                .route("test request", "", "backend", "claude-sonnet-5-5", null);

        assertEquals(expectedModel, decision.selectedModel);
        assertEquals("CLAUDE", decision.router);
    }

    private static Map<String, Object> classifierResponse(String label) {
        return Map.of("choices", List.of(Map.of("message", Map.of(
                "content", "CLASSIFICATION: " + label + "\nCONFIDENCE: HIGH\nREASONING: test"))));
    }
}
