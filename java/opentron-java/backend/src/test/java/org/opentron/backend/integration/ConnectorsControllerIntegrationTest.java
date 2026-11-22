package org.opentron.backend.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentron.backend.OpentronBackendApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.opentron.backend.storage.service.ConnectorStateService;
import org.opentron.backend.storage.repositories.ConnectorStateRepository;
import org.opentron.backend.services.OAuthService;
import org.opentron.backend.services.IngestService;
import static org.mockito.Mockito.when;
import org.opentron.backend.util.EngineRouting;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = OpentronBackendApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.flyway.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConnectorsControllerIntegrationTest {

    private static HttpServer engineStub;

    @BeforeAll
    public void startStub() throws Exception {
        if (engineStub == null) {
            engineStub = HttpServer.create(new InetSocketAddress(0), 0);
            engineStub.createContext("/", new ForwardAllHandler());
            engineStub.setExecutor(r -> new Thread(r));
            engineStub.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (engineStub != null) engineStub.stop(0);
            }));
        }
    }

    @AfterAll
    public void stopStub() {
        if (engineStub != null) engineStub.stop(0);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        try {
            if (engineStub == null) {
                engineStub = HttpServer.create(new InetSocketAddress(0), 0);
                engineStub.createContext("/", new ForwardAllHandler());
                engineStub.setExecutor(r -> new Thread(r));
                engineStub.start();
            }
            int port = engineStub.getAddress().getPort();
            registry.add("engine.host", () -> "http://127.0.0.1:" + port);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Value("${local.server.port}")
    int localPort;

    @MockBean
    ConnectorStateService connectorStateService;

    @MockBean
    ConnectorStateRepository connectorStateRepo;

    @MockBean
    OAuthService oauthService;

    @MockBean
    IngestService ingestService;

    @MockBean
    EngineRouting engineRouting;

    @Test
    public void connectorsEndpointShouldNotBeProxiedToEngine() {
        when(connectorStateService.listConnectors()).thenReturn(java.util.List.of());

        WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + localPort).build();
        String response = client.get().uri("/v1/connectors")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(5));

        assertNotNull(response);
        assertTrue(response.contains("\"connectors\""));
        assertTrue(response.contains("[]"));
    }

    @Test
    public void archiveEvidenceEndpointReturnsImportedSources() {
        when(ingestService.searchArchive("Mara lantern", 5)).thenReturn(java.util.List.of(
            java.util.Map.of(
                "file_name", "journal.csv",
                "excerpt", "Mara and I watched the paper lanterns.",
                "chunk_index", 0
            )
        ));

        WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + localPort).build();
        String response = client.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/connectors/upload/ingest/search")
                        .queryParam("q", "Mara lantern")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(5));

        assertNotNull(response);
        assertTrue(response.contains("\"file_name\":\"journal.csv\""));
        assertTrue(response.contains("\"excerpt\":\"Mara and I watched the paper lanterns.\""));
    }

    @Test
    public void archiveEvidenceEndpointRejectsBlankQuestions() {
        WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + localPort).build();
        int status = client.get()
                .uri("/v1/connectors/upload/ingest/search?q=+")
                .exchangeToMono(response -> reactor.core.publisher.Mono.just(response.statusCode().value()))
                .block(Duration.ofSeconds(5));

        assertEquals(400, status);
    }

    @Test
    public void uploadFilesEndpointRejectsEmptyFileList() {
        WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + localPort).build();
        int status = client.post()
                .uri("/v1/connectors/upload/ingest/files")
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .bodyValue(new org.springframework.util.LinkedMultiValueMap<>())
                .exchangeToMono(response -> reactor.core.publisher.Mono.just(response.statusCode().value()))
                .block(Duration.ofSeconds(5));

        assertEquals(400, status);
    }

    @Test
    public void ingestJobStatusEndpointReturns404ForUnknownJob() {
        when(ingestService.getJobDetails("does-not-exist")).thenReturn(
                java.util.Map.of("status", "unknown", "message", "Job not found")
        );

        WebClient client = WebClient.builder().baseUrl("http://127.0.0.1:" + localPort).build();
        int status = client.get()
                .uri("/v1/connectors/upload/ingest/jobs/does-not-exist")
                .exchangeToMono(response -> reactor.core.publisher.Mono.just(response.statusCode().value()))
                .block(Duration.ofSeconds(5));

        assertEquals(404, status);
    }

    static class ForwardAllHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = "{\"forwarded\":true}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
