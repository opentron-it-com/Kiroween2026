package org.opentron.backend.llm.anthropic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicClientTest {

    @Test
    void truncateForAnthropicAddsMarkerWhenTextExceedsLimit() {
        String original = "x".repeat(300);

        String truncated = AnthropicClient.truncateForAnthropic(original, 100);

        assertTrue(truncated.startsWith("x".repeat(100)));
        assertTrue(truncated.contains("[truncated"));
        assertTrue(truncated.length() > 100);
    }

    @Test
    void truncateForAnthropicLeavesShortTextUntouched() {
        String original = "short prompt";

        String truncated = AnthropicClient.truncateForAnthropic(original, 100);

        assertEquals(original, truncated);
    }

    @Test
    void defaultsToSonnet55AndAcceptsFable52() {
        assertEquals("claude-sonnet-5-5", new AnthropicClient(null, "test-key").getModelName());
        assertEquals("claude-fable-5-2", new AnthropicClient("claude-fable-5-2", "test-key").getModelName());
    }
}
