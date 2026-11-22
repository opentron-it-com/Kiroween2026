package org.opentron.backend.services;

import org.junit.jupiter.api.Test;
import org.opentron.backend.util.CloudModelService;
import org.opentron.backend.util.OllamaCliService;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelSelectorServiceTest {

    @Test
    void selectsAnthropicCloudModelWhenAnthropicApiKeyConfigured() {
        OllamaCliService ollamaService = mock(OllamaCliService.class);
        CloudModelService cloudModelService = mock(CloudModelService.class);

        when(ollamaService.listModels()).thenReturn(Mono.just(List.of()));
        when(cloudModelService.isInternetAllowed()).thenReturn(true);
        when(cloudModelService.listModels()).thenReturn(Mono.just(List.of()));
        when(cloudModelService.listModels(anyMap())).thenReturn(Mono.just(List.of()));
        when(cloudModelService.hasApiKey(eq("openrouter"), anyMap())).thenReturn(false);
        when(cloudModelService.hasApiKey(eq("google"), anyMap())).thenReturn(false);
        when(cloudModelService.hasApiKey(eq("anthropic"), anyMap())).thenReturn(true);

        ModelSelectorService service = new ModelSelectorService(ollamaService, cloudModelService);

        assertEquals("claude-sonnet-5-5", service.selectBestModel("backend", java.util.Map.of("anthropic", "test-key")));
    }

    @Test
    void selectsRequestedLocalRoleModelsWhenInternetAccessIsDisabled() {
        OllamaCliService ollamaService = mock(OllamaCliService.class);
        CloudModelService cloudModelService = mock(CloudModelService.class);

        when(ollamaService.listModels()).thenReturn(Mono.just(List.of(
                "qwen2.5-coder:7b", "deepseek-r1:8b", "gemma3:4b", "qwen3:4b")));
        when(cloudModelService.isInternetAllowed()).thenReturn(false);

        ModelSelectorService service = new ModelSelectorService(ollamaService, cloudModelService);

        assertEquals("qwen2.5-coder:7b", service.selectBestModel("backend", java.util.Map.of("anthropic", "test-key")));
        assertEquals("gemma3:4b", service.selectBestModel("knowledge", java.util.Map.of("anthropic", "test-key")));
    }

    @Test
    void selectsLatestGeminiFallbackWhenProviderListingIsEmpty() {
        OllamaCliService ollamaService = mock(OllamaCliService.class);
        CloudModelService cloudModelService = mock(CloudModelService.class);

        when(ollamaService.listModels()).thenReturn(Mono.just(List.of()));
        when(cloudModelService.isInternetAllowed()).thenReturn(true);
        when(cloudModelService.listModels(anyMap())).thenReturn(Mono.just(List.of()));
        when(cloudModelService.hasApiKey(eq("openrouter"), anyMap())).thenReturn(false);
        when(cloudModelService.hasApiKey(eq("google"), anyMap())).thenReturn(true);
        when(cloudModelService.hasApiKey(eq("anthropic"), anyMap())).thenReturn(false);

        ModelSelectorService service = new ModelSelectorService(ollamaService, cloudModelService);

        assertEquals("gemini-4-hydrogen", service.selectBestModel("backend", java.util.Map.of("google", "test-key")));
    }

    @Test
    void selectsAvailableGeminiCarbonWhenProviderListsIt() {
        OllamaCliService ollamaService = mock(OllamaCliService.class);
        CloudModelService cloudModelService = mock(CloudModelService.class);

        when(ollamaService.listModels()).thenReturn(Mono.just(List.of()));
        when(cloudModelService.isInternetAllowed()).thenReturn(true);
        when(cloudModelService.listModels(anyMap())).thenReturn(Mono.just(List.of("gemini-4-carbon")));
        when(cloudModelService.hasApiKey(eq("openrouter"), anyMap())).thenReturn(false);
        when(cloudModelService.hasApiKey(eq("google"), anyMap())).thenReturn(true);
        when(cloudModelService.hasApiKey(eq("anthropic"), anyMap())).thenReturn(false);

        ModelSelectorService service = new ModelSelectorService(ollamaService, cloudModelService);

        assertEquals("gemini-4-carbon", service.selectBestModel("backend", java.util.Map.of("google", "test-key")));
    }
}
