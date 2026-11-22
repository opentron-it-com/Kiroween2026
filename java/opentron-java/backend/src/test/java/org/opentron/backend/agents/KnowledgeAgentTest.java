package org.opentron.backend.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.opentron.backend.memory.MemoryService;
import org.opentron.backend.memory.MemoryStoreRequest;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void includesUploadedArchiveMatchesInTheAssistantContext() {
        MemoryService memoryService = new MemoryService(tempDir.resolve("memory.jsonl").toString());
        MemoryStoreRequest archiveEntry = new MemoryStoreRequest();
        archiveEntry.setText("The Ghostbook archive passphrase is GHOSTBOOK-ARCHIVE-MARKER.");
        archiveEntry.setMetadata(Map.of(
            "source", "file_upload",
            "file_name", "sample-notes.txt",
            "chunk_index", 0
        ));
        memoryService.store(archiveEntry);

        AgentLLMBridge llmBridge = mock(AgentLLMBridge.class);
        when(llmBridge.getModel()).thenReturn("test-model");
        when(llmBridge.queryLLMStream(
            anyString(),
            anyString(),
            anyInt(),
            any(Consumer.class),
            any(BiConsumer.class),
            any(Consumer.class)
        )).thenReturn(new HashMap<>(Map.of("response", "The marker is GHOSTBOOK-ARCHIVE-MARKER.")));

        KnowledgeAgent agent = new KnowledgeAgent(llmBridge, memoryService);
        agent.process(new AgentMessage("knowledge", "task", Map.of(
            "task", "What is the Ghostbook archive passphrase?"
        )));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmBridge).queryLLMStream(
            anyString(),
            prompt.capture(),
            anyInt(),
            any(Consumer.class),
            any(BiConsumer.class),
            any(Consumer.class)
        );
        assertTrue(prompt.getValue().contains("[Source: sample-notes.txt]"));
        assertTrue(prompt.getValue().contains("GHOSTBOOK-ARCHIVE-MARKER"));
    }
}
