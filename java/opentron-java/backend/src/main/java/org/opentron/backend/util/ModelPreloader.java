package org.opentron.backend.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * ModelPreloader: Warms all role-specific Ollama models on startup.
 * Ollama may evict models under memory pressure.
 */
@Component
public class ModelPreloader implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ModelPreloader.class);
    private static final int PULL_READ_TIMEOUT_MS = 30 * 60 * 1000;

    // Warm each role model for five minutes; Ollama can evict under memory pressure.
    private static final List<String> MODELS = Arrays.asList(
        "qwen3:4b",
        "gemma3:4b",
        "qwen2.5-coder:7b",
        "deepseek-r1:8b"
    );

    private final ObjectMapper objectMapper;
    private final String ollamaHost;

    @Autowired
    public ModelPreloader(
            ObjectMapper objectMapper,
            @Value("${engine.host:http://127.0.0.1:11434}") String ollamaHost) {
        this.objectMapper = objectMapper;
        this.ollamaHost = ollamaHost.replaceAll("/+$", "");
    }

    @Override
    public void run(String... args) {
        // Warm models before the app accepts requests.
        logger.info("Warming role models before startup...");
        try {
            Thread.sleep(2000); // Wait 2 seconds for Ollama to stabilize
            preloadAllModels(); // Blocks until complete
            logger.info("Model preload phase completed.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Model preload interrupted", e);
            throw new IllegalStateException("Model preload interrupted", e);
        }
    }

    private void preloadAllModels() {
        logger.info("========================================");
        logger.info("Starting Ollama model warmup");
        logger.info("Warming {} role models; Ollama may evict them under memory pressure", MODELS.size());
        logger.info("========================================");

        long startTime = System.currentTimeMillis();
        int loaded = 0;

        for (String model : MODELS) {
            long modelStart = System.currentTimeMillis();
            boolean available;
            try {
                available = ensureModelAvailable(model);
            } catch (IOException e) {
                logger.warn("✗ {} availability check or pull failed", model, e);
                continue;
            }
            if (!available) {
                logger.warn("✗ {} is unavailable; skipping preload", model);
                continue;
            }
            boolean success = preloadModel(model);
            long modelDuration = System.currentTimeMillis() - modelStart;

            if (success) {
                loaded++;
                logger.info("✓ {} loaded in {}ms", model, modelDuration);
            } else {
                logger.warn("✗ {} failed to load", model);
            }
        }

        long totalDuration = System.currentTimeMillis() - startTime;
        logger.info("========================================");
        logger.info("Preload Complete: {}/{} models loaded in {}s",
            loaded, MODELS.size(), totalDuration / 1000.0);
        if (loaded == MODELS.size()) {
            logger.info("All role models completed warmup");
        } else {
            logger.warn("{} of {} preload models are unavailable; check Ollama connectivity and logs.",
                MODELS.size() - loaded, MODELS.size());
        }
        logger.info("========================================");
    }

    boolean ensureModelAvailable(String model) throws IOException {
        HttpURLConnection tagsConnection = openConnection("/api/tags", "GET", 5000);
        try {
            int code = tagsConnection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                logger.warn("Unable to check Ollama models (HTTP {})", code);
                return false;
            }

            JsonNode tags;
            try (InputStream response = tagsConnection.getInputStream()) {
                tags = objectMapper.readTree(response);
            }
            JsonNode installedModels = tags.path("models");
            for (JsonNode installedModel : installedModels) {
                if (model.equals(installedModel.path("name").asText())) {
                    return true;
                }
            }
        } finally {
            tagsConnection.disconnect();
        }

        logger.info("{} is not installed in Ollama; pulling it now", model);
        return pullModel(model);
    }

    private boolean pullModel(String model) throws IOException {
        HttpURLConnection connection = openConnection("/api/pull", "POST", PULL_READ_TIMEOUT_MS);
        try {
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            byte[] request = objectMapper.writeValueAsBytes(
                objectMapper.createObjectNode().put("model", model).put("stream", false));
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                logger.warn("Ollama model pull failed for {} (HTTP {})", model, code);
                return false;
            }

            try (InputStream response = connection.getInputStream()) {
                objectMapper.readTree(response);
            }
            logger.info("Ollama model pull completed for {}", model);
            return true;
        } finally {
            connection.disconnect();
        }
    }

    private boolean preloadModel(String model) {
        HttpURLConnection conn = null;
        try {
            conn = openConnection("/api/generate", "POST", 180000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            byte[] request = objectMapper.writeValueAsBytes(
                objectMapper.createObjectNode()
                    .put("model", model)
                    .put("prompt", "hi")
                    .put("stream", false)
                    .put("keep_alive", "5m"));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(request);
            }

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    while (reader.readLine() != null) {
                        // Drain response to completion to wait for model loading.
                    }
                }
                return true;
            }

            logger.warn("Model {} HTTP {}", model, code);
            return false;
        } catch (java.io.InterruptedIOException e) {
            logger.warn("Model {} load interrupted (timeout)", model);
            return false;
        } catch (IOException e) {
            logger.warn("Model {} load error: {}", model, e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(String path, String method, int readTimeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(ollamaHost + path).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(readTimeout);
        return connection;
    }
}
