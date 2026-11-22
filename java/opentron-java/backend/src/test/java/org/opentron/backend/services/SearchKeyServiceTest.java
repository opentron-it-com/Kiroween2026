package org.opentron.backend.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchKeyServiceTest {

    @Test
    void savesKeyAndProviderToRelativeEnvFile() throws Exception {
        Path envFile = Path.of(".search-key-" + UUID.randomUUID() + ".env");
        try {
            SearchKeyService service = new SearchKeyService(envFile);

            service.saveKey("test-key", "tavily");

            assertEquals("test-key", service.loadKey());
            assertEquals("tavily", service.loadProvider());
        } finally {
            Files.deleteIfExists(envFile);
        }
    }
}
