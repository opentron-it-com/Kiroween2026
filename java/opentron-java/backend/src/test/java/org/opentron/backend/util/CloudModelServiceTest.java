package org.opentron.backend.util;

import org.junit.jupiter.api.Test;
import org.opentron.backend.services.NetworkPolicyService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CloudModelServiceTest {

    @Test
    void buildAnthropicPayloadMovesSystemPromptToTopLevel() {
        // Provide a NetworkPolicyService to match CloudModelService constructor
        NetworkPolicyService policy = new NetworkPolicyService(true);
        CloudModelService service = new CloudModelService(policy);

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", "You are a helpful assistant"),
            Map.of("role", "user", "content", "Hello there")
        );

        Map<String, Object> payload = service.buildAnthropicPayload("claude-sonnet-5", messages, 2000);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> systemBlocks = (List<Map<String, Object>>) payload.get("system");
        assertEquals(1, systemBlocks.size());
        assertEquals("text", systemBlocks.get(0).get("type"));
        assertEquals("You are a helpful assistant", systemBlocks.get(0).get("text"));
        assertEquals(Map.of("type", "ephemeral"), systemBlocks.get(0).get("cache_control"));
        assertFalse(payload.containsKey("temperature"), "Anthropic payload should not include deprecated temperature");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anthropicMessages = (List<Map<String, Object>>) payload.get("messages");
        assertEquals(1, anthropicMessages.size());
        assertEquals("user", anthropicMessages.get(0).get("role"));
        assertEquals("Hello there", anthropicMessages.get(0).get("content"));
    }
}
