package org.opentron.backend.util;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class EngineRoutingTest {

    @Test
    void translatesOllamaModelPullToPullEndpoint() {
        EngineRouting routing = new EngineRouting(
                mock(WebClient.class), "http://127.0.0.1:11434", "auto");

        assertEquals("/api/pull", routing.translateRequestPath("/v1/models/pull"));
    }

    @Test
    void keepsOpenAiModelPullPathUnchanged() {
        EngineRouting routing = new EngineRouting(
                mock(WebClient.class), "https://api.openai.com/v1", "openai");

        assertEquals("/v1/models/pull", routing.translateRequestPath("/v1/models/pull"));
    }
}
