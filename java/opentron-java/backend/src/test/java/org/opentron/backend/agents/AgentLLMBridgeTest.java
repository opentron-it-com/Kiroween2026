package org.opentron.backend.agents;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.opentron.backend.llm.anthropic.AnthropicClient;
import org.opentron.backend.util.CloudModelService;
import org.opentron.backend.util.HuggingFaceService;
import org.opentron.backend.util.OllamaCliService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AgentLLMBridgeTest {

    @Test
    void usesCloudModelServiceForClaudeModels() {
        OllamaCliService ollamaService = mock(OllamaCliService.class);
        HuggingFaceService huggingFaceService = mock(HuggingFaceService.class);
        CloudModelService cloudModelService = mock(CloudModelService.class);
        when(cloudModelService.isInternetAllowed()).thenReturn(true);

        Map<String, Object> classifierResponse = Map.of(
            "choices", List.of(Map.of("message", Map.of("content", "CLASSIFICATION: COMPLEX\nCONFIDENCE: HIGH\nREASONING: Complex request")))
        );

        when(ollamaService.chatCompletion(anyString(), anyList()))
            .thenReturn(Mono.just(classifierResponse));

        try (MockedConstruction<AnthropicClient> mockedConstruction =
                 Mockito.mockConstruction(AnthropicClient.class, (mock, context) -> {
                     when(mock.queryStream(anyString(), anyString(), anyInt(), any()))
                         .thenReturn(Map.of(
                             "status", "completed",
                             "response", "Claude response",
                             "model", "claude-sonnet-5",
                             "provider", "anthropic",
                             "tokens_used", 10,
                             "cost_usd", 0.0
                         ));
                 })) {
            AgentLLMBridge bridge = new AgentLLMBridge(
                ollamaService,
                huggingFaceService,
                cloudModelService,
                "claude-sonnet-5",
                Map.of("anthropic", "test-key")
            );

            Map<String, Object> result = bridge.queryLLM("system", "hello", 256);

            assertEquals("completed", result.get("status"));
            assertEquals("Claude response", result.get("response"));
            assertTrue(result.containsKey("routing_decision"));
            assertEquals("CLAUDE", result.get("routing_decision"));
            verify(cloudModelService, never()).callCloudModel(anyString(), anyList(), anyMap());
            assertEquals(1, mockedConstruction.constructed().size());
        }
    }

    @Test
    void usesAnthropicClientForClaudeModels() {
        OllamaCliService ollamaService = mock(OllamaCliService.class);
        HuggingFaceService huggingFaceService = mock(HuggingFaceService.class);
        CloudModelService cloudModelService = mock(CloudModelService.class);
        when(cloudModelService.isInternetAllowed()).thenReturn(true);

        Map<String, Object> classifierResponse = Map.of(
            "choices", List.of(Map.of("message", Map.of("content", "CLASSIFICATION: COMPLEX\nCONFIDENCE: HIGH\nREASONING: Complex request")))
        );

        when(ollamaService.chatCompletion(anyString(), anyList()))
            .thenReturn(Mono.just(classifierResponse));

        try (MockedConstruction<AnthropicClient> mockedConstruction =
                 Mockito.mockConstruction(AnthropicClient.class, (mock, context) -> {
                     when(mock.queryStream(anyString(), anyString(), anyInt(), any()))
                         .thenReturn(Map.of(
                             "status", "completed",
                             "response", "Claude response",
                             "model", "claude-sonnet-5",
                             "provider", "anthropic",
                             "tokens_used", 10,
                             "cost_usd", 0.0
                         ));
                 })) {

            AgentLLMBridge bridge = new AgentLLMBridge(
                ollamaService,
                huggingFaceService,
                cloudModelService,
                "claude-sonnet-5",
                Map.of("anthropic", "test-key")
            );

            Map<String, Object> result = bridge.queryLLM("system", "hello", 256);

            assertEquals("completed", result.get("status"));
            assertEquals("Claude response", result.get("response"));
            assertEquals("CLAUDE", result.get("routing_decision"));
            // Classifier should have been invoked on Ollama for complexity triage
            verify(ollamaService).chatCompletion(anyString(), anyList());
            verify(cloudModelService, never()).callCloudModel(anyString(), anyList(), anyMap());
            assertEquals(1, mockedConstruction.constructed().size());
        }
    }

    @Test
    void usesCloudModelServiceForClaudeModelsWhenKeyUsesClaudeAlias() {
        OllamaCliService ollamaService = mock(OllamaCliService.class);
        HuggingFaceService huggingFaceService = mock(HuggingFaceService.class);
        CloudModelService cloudModelService = mock(CloudModelService.class);
        when(cloudModelService.isInternetAllowed()).thenReturn(true);

        Map<String, Object> classifierResponse = Map.of(
            "choices", List.of(Map.of("message", Map.of("content", "CLASSIFICATION: COMPLEX\nCONFIDENCE: HIGH\nREASONING: Complex request")))
        );

        when(ollamaService.chatCompletion(anyString(), anyList()))
            .thenReturn(Mono.just(classifierResponse));

        try (MockedConstruction<AnthropicClient> mockedConstruction =
                 Mockito.mockConstruction(AnthropicClient.class, (mock, context) -> {
                     when(mock.queryStream(anyString(), anyString(), anyInt(), any()))
                         .thenReturn(Map.of(
                             "status", "completed",
                             "response", "Claude alias response",
                             "model", "claude-sonnet-5",
                             "provider", "anthropic",
                             "tokens_used", 10,
                             "cost_usd", 0.0
                         ));
                 })) {
            AgentLLMBridge bridge = new AgentLLMBridge(
                ollamaService,
                huggingFaceService,
                cloudModelService,
                "claude-sonnet-5",
                Map.of("claude", "test-key")
            );

            Map<String, Object> result = bridge.queryLLM("system", "hello", 256);

            assertEquals("completed", result.get("status"));
            assertEquals("Claude alias response", result.get("response"));
            assertEquals("CLAUDE", result.get("routing_decision"));
            verify(cloudModelService, never()).callCloudModel(anyString(), anyList(), anyMap());
            assertEquals(1, mockedConstruction.constructed().size());
        }
    }

    @Test
    void keepsSimpleRequestsOnOllamaEvenWhenClaudeModelIsSelected() {
        OllamaCliService ollamaService = mock(OllamaCliService.class);
        HuggingFaceService huggingFaceService = mock(HuggingFaceService.class);
        CloudModelService cloudModelService = mock(CloudModelService.class);
        when(cloudModelService.isInternetAllowed()).thenReturn(true);

        Map<String, Object> classifierResponse = Map.of(
            "choices", List.of(Map.of("message", Map.of("content", "CLASSIFICATION: SIMPLE\nCONFIDENCE: HIGH\nREASONING: Simple request")))
        );
        
        when(ollamaService.chatCompletion(anyString(), anyList()))
            .thenReturn(Mono.just(classifierResponse));

        try (MockedConstruction<AnthropicClient> mockedConstruction =
                 Mockito.mockConstruction(AnthropicClient.class, (mock, context) -> {
                     when(mock.queryStream(anyString(), anyString(), anyInt(), any()))
                         .thenReturn(Map.of(
                             "status", "completed",
                             "response", "Claude response",
                             "model", "claude-sonnet-5",
                             "provider", "anthropic",
                             "tokens_used", 10,
                             "cost_usd", 0.0
                         ));
                 })) {
            AgentLLMBridge bridge = new AgentLLMBridge(
                ollamaService,
                huggingFaceService,
                cloudModelService,
                "claude-sonnet-5",
                Map.of("anthropic", "test-key")
            );

            Map<String, Object> result = bridge.queryLLM("system", "hello", 256);

            assertEquals("completed", result.get("status"));
            assertEquals("Claude response", result.get("response"));
            assertEquals("CLAUDE", result.get("routing_decision"));
            assertEquals("SIMPLE", result.get("classification"));
            assertEquals(1, mockedConstruction.constructed().size());
        }
    }
}
