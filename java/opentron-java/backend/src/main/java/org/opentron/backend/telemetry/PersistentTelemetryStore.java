package org.opentron.backend.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Persistent telemetry store using JSON file.
 * Reloads from disk on each read to always get fresh data.
 *
 * Tracks the real prompt/completion token split and real accumulated cost
 * (in USD, stored as micros to keep everything as whole-number Longs) 
 */
@Component
public class PersistentTelemetryStore {

    private static final Logger logger = LoggerFactory.getLogger(PersistentTelemetryStore.class);

    private static final String DATA_DIR = System.getProperty("user.home") + "/.opentron";
    private static final String TELEMETRY_FILE = DATA_DIR + "/telemetry.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String KEY_REQUESTS = "total_requests";
    private static final String KEY_TOKENS = "total_tokens";
    private static final String KEY_PROMPT_TOKENS = "total_prompt_tokens";
    private static final String KEY_COMPLETION_TOKENS = "total_completion_tokens";
    private static final String KEY_ENERGY_J = "total_energy_j";
    private static final String KEY_COST_MICROS = "total_cost_micros"; // USD * 1,000,000, kept as a Long

    private final Map<String, Long> data;

    public PersistentTelemetryStore() {
        this.data = loadFromDisk();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> loadFromDisk() {
        try {
            Files.createDirectories(Paths.get(DATA_DIR));
            File file = new File(TELEMETRY_FILE);
            if (file.exists()) {
                String content = new String(Files.readAllBytes(file.toPath()));
                Map<String, Long> loaded = mapper.readValue(content, Map.class);
                return loaded;
            }
        } catch (IOException e) {
            logger.warn("Error loading telemetry", e);
        }
        return defaults();
    }

    private Map<String, Long> defaults() {
        Map<String, Long> d = new HashMap<>();
        d.put(KEY_REQUESTS, 0L);
        d.put(KEY_TOKENS, 0L);
        d.put(KEY_PROMPT_TOKENS, 0L);
        d.put(KEY_COMPLETION_TOKENS, 0L);
        d.put(KEY_ENERGY_J, 0L);
        d.put(KEY_COST_MICROS, 0L);
        return d;
    }

    private void saveToDisk() {
        try {
            Files.createDirectories(Paths.get(DATA_DIR));
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.write(Paths.get(TELEMETRY_FILE), json.getBytes());
        } catch (IOException e) {
            logger.warn("Error saving telemetry", e);
        }
    }

    private static long asLong(Object val) {
        if (val instanceof Number) return ((Number) val).longValue();
        return 0L;
    }

    private void addToKey(String key, long delta) {
        synchronized (data) {
            data.put(key, asLong(data.get(key)) + delta);
            saveToDisk();
        }
    }

    private long readKey(String key) {
        try {
            Map<String, Long> fresh = loadFromDisk();
            return asLong(fresh.get(key));
        } catch (Exception e) {
            logger.warn("Error reading telemetry key {}", key, e);
            return 0L;
        }
    }

    /** Legacy blended counter. Prefer addTokenUsage() so the split is real. */
    public void addTokens(long tokens) {
        addToKey(KEY_TOKENS, tokens);
    }

    /**
     * Records a real prompt/completion split plus the real dollar cost of
     * this request (already computed from actual token counts and a
     * versioned provider price, via PricingService).
     */
    public void addTokenUsage(long promptTokens, long completionTokens, double costUsd) {
        synchronized (data) {
            data.put(KEY_PROMPT_TOKENS, asLong(data.get(KEY_PROMPT_TOKENS)) + promptTokens);
            data.put(KEY_COMPLETION_TOKENS, asLong(data.get(KEY_COMPLETION_TOKENS)) + completionTokens);
            data.put(KEY_TOKENS, asLong(data.get(KEY_TOKENS)) + promptTokens + completionTokens);
            long costMicros = Math.round(costUsd * 1_000_000.0);
            data.put(KEY_COST_MICROS, asLong(data.get(KEY_COST_MICROS)) + costMicros);
            saveToDisk();
        }
    }

    public void recordRequest() {
        addToKey(KEY_REQUESTS, 1L);
    }

    public void addEnergyJ(long energyJ) {
        addToKey(KEY_ENERGY_J, energyJ);
    }

    private static final String PROVIDER_COST_KEY_PREFIX = "provider_cost_micros_";

    /**
     * Accumulates REAL accrued cost for a provider family (see
     * PricingService.providerGroupFor), keyed dynamically rather than via a
     * fixed KEY_* constant so new provider groups don't need a store change.
     * This is separate from KEY_COST_MICROS (the blended total), it's
     * what backs a real, per-provider "money actually spent" breakdown,
     * as opposed to the counterfactual "what would this usage have cost on
     * every known provider" comparison PricingService.allPrices() computes.
     */
    public void addProviderCost(String providerGroup, double costUsd) {
        if (providerGroup == null || providerGroup.isBlank()) return;
        long costMicros = Math.round(costUsd * 1_000_000.0);
        if (costMicros == 0) return;
        addToKey(PROVIDER_COST_KEY_PREFIX + providerGroup, costMicros);
    }

    /** Real accumulated cost in USD per provider group, for groups that have had at least one real recorded request. */
    public Map<String, Double> getProviderCosts() {
        Map<String, Long> fresh = loadFromDisk();
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, Long> entry : fresh.entrySet()) {
            if (entry.getKey().startsWith(PROVIDER_COST_KEY_PREFIX)) {
                String group = entry.getKey().substring(PROVIDER_COST_KEY_PREFIX.length());
                result.put(group, asLong(entry.getValue()) / 1_000_000.0);
            }
        }
        return result;
    }

    public long getTotalTokens() {
        return readKey(KEY_TOKENS);
    }

    public long getTotalPromptTokens() {
        return readKey(KEY_PROMPT_TOKENS);
    }

    public long getTotalCompletionTokens() {
        return readKey(KEY_COMPLETION_TOKENS);
    }

    public long getTotalRequests() {
        return readKey(KEY_REQUESTS);
    }

    public long getTotalEnergyJ() {
        return readKey(KEY_ENERGY_J);
    }

    /** Real accumulated cost in USD, derived from real per-request token counts and versioned pricing. */
    public double getTotalCostUsd() {
        return readKey(KEY_COST_MICROS) / 1_000_000.0;
    }

    public void reset() {
        synchronized (data) {
            data.clear();
            data.putAll(defaults());
            saveToDisk();
        }
    }
}
