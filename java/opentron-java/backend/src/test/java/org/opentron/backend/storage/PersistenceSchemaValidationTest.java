package org.opentron.backend.storage;

import org.junit.jupiter.api.Test;
import org.opentron.backend.storage.entities.AgentMemory;
import org.opentron.backend.storage.entities.TraceLog;
import org.opentron.backend.storage.repositories.AgentMemoryRepository;
import org.opentron.backend.storage.repositories.TraceLogRepository;
import org.opentron.backend.util.EngineRouting;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:persistence-schema-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect")
class PersistenceSchemaValidationTest {

    @Autowired
    private AgentMemoryRepository agentMemoryRepository;

    @Autowired
    private TraceLogRepository traceLogRepository;

    @MockBean
    private EngineRouting engineRouting;

    @Test
    void validatesFlywaySchemaAndPersistsBinaryEntities() {
        byte[] embedding = {1, 2, 3};
        AgentMemory memory = new AgentMemory("test-agent", "raw trace", "summary");
        memory.setEmbedding(embedding);

        AgentMemory savedMemory = agentMemoryRepository.saveAndFlush(memory);
        assertNotNull(savedMemory.getId());
        assertArrayEquals(embedding, agentMemoryRepository.findById(savedMemory.getId()).orElseThrow().getEmbedding());

        byte[] compressedData = {4, 5, 6};
        TraceLog trace = new TraceLog("test-agent", "input", "output", 10);
        trace.setCompressedData(compressedData);

        TraceLog savedTrace = traceLogRepository.saveAndFlush(trace);
        assertNotNull(savedTrace.getId());
        assertArrayEquals(compressedData, traceLogRepository.findById(savedTrace.getId()).orElseThrow().getCompressedData());
    }
}
