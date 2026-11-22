package org.opentron.backend.orchestration;

import org.junit.jupiter.api.Test;
import org.opentron.backend.util.OllamaCliService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplexityClassifierNodeTest {

    @Test
    void usesQwen3ForTriageAndRecognizesExtremeClassification() {
        OllamaCliService ollama = mock(OllamaCliService.class);
        when(ollama.chatCompletion(eq("qwen3:4b"), anyList()))
                .thenReturn(Mono.just(classifierResponse("EXTREME")));

        ComplexityClassifierNode.ComplexityClassification result =
                new ComplexityClassifierNode(ollama).classify("Investigate a production anomaly").block();

        assertTrue(result.isExtreme());
        assertTrue(result.isComplex());
        verify(ollama).chatCompletion(eq("qwen3:4b"), anyList());
    }

    private static Map<String, Object> classifierResponse(String label) {
        return Map.of("choices", List.of(Map.of("message", Map.of(
                "content", "CLASSIFICATION: " + label + "\nCONFIDENCE: HIGH\nREASONING: test"))));
    }
}
