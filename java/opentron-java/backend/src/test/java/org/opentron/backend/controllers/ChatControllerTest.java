package org.opentron.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.opentron.backend.dto.ChatCompletionRequest;
import org.opentron.backend.pricing.PricingService;
import org.opentron.backend.util.CloudModelService;
import org.opentron.backend.util.EngineRouting;
import org.opentron.backend.util.HuggingFaceService;
import org.opentron.backend.util.OllamaCliService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

class ChatControllerTest {

    @Test
    void ollamaCliReceivesTopLevelModelAndMessages() {
        EngineRouting engineRouting = mock(EngineRouting.class);
        OllamaCliService ollamaCliService = mock(OllamaCliService.class);
        when(engineRouting.getEffectiveEngineType()).thenReturn(EngineRouting.EngineType.OLLAMA);
        when(ollamaCliService.chatCompletion(eq("qwen3:4b"), anyList()))
                .thenReturn(Mono.just(Map.of("choices", List.of(), "usage", Map.of())));

        ChatController controller = newController(engineRouting, ollamaCliService);
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("qwen3:4b");
        request.setMessages(List.of(message("user", "Recall the Lantern Fair entry.")));

        controller.completionsStream(new MockHttpServletRequest("POST", "/v1/chat/completions"), request).block();

        verify(ollamaCliService).chatCompletion(
                eq("qwen3:4b"),
                eq(List.of(Map.of("role", "user", "content", "Recall the Lantern Fair entry."))));
    }

    @Test
    void ollamaCliRejectsRequestsWithoutMessages() {
        EngineRouting engineRouting = mock(EngineRouting.class);
        OllamaCliService ollamaCliService = mock(OllamaCliService.class);
        when(engineRouting.getEffectiveEngineType()).thenReturn(EngineRouting.EngineType.OLLAMA);
        ChatController controller = newController(engineRouting, ollamaCliService);

        var response = controller.completionsStream(
                new MockHttpServletRequest("POST", "/v1/chat/completions"),
                new ChatCompletionRequest()).block();

        assertEquals(400, response.getStatusCode().value());
        verify(ollamaCliService, never()).chatCompletion(eq("gemma3:4b"), anyList());
    }

    private ChatController newController(EngineRouting engineRouting, OllamaCliService ollamaCliService) {
        return new ChatController(
                mock(WebClient.class),
                engineRouting,
                ollamaCliService,
                mock(HuggingFaceService.class),
                mock(CloudModelService.class),
                mock(PricingService.class));
    }

    private ChatCompletionRequest.Message message(String role, String content) {
        ChatCompletionRequest.Message message = new ChatCompletionRequest.Message();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
