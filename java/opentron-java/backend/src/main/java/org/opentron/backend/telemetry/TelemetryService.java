package org.opentron.backend.telemetry;

import org.springframework.stereotype.Service;

/**
 * TelemetryService now delegates to PersistentTelemetryStore.
 * All data is persisted to disk automatically.
 */
@Service
public class TelemetryService {

    private final PersistentTelemetryStore store;

    public TelemetryService(PersistentTelemetryStore store) {
        this.store = store;
    }

    public void recordRequest() {
        store.recordRequest();
    }

    /** Legacy blended counter. Prefer addTokenUsage() so downstream consumers get a real split. */
    public void addTokens(long tokens) {
        store.addTokens(tokens);
    }

    /** Records a real prompt/completion split and the real dollar cost of the request. */
    public void addTokenUsage(long promptTokens, long completionTokens, double costUsd) {
        store.addTokenUsage(promptTokens, completionTokens, costUsd);
    }

    public void addEnergyJ(long j) {
        store.addEnergyJ(j);
    }

    /** Real accrued cost for a provider family -- see PersistentTelemetryStore.addProviderCost. */
    public void addProviderCost(String providerGroup, double costUsd) {
        store.addProviderCost(providerGroup, costUsd);
    }

    /** Real accumulated cost in USD per provider group -- see PersistentTelemetryStore.getProviderCosts. */
    public java.util.Map<String, Double> getProviderCosts() {
        return store.getProviderCosts();
    }

    public long getTotalRequests() {
        return store.getTotalRequests();
    }

    public long getTotalTokens() {
        return store.getTotalTokens();
    }

    public long getTotalPromptTokens() {
        return store.getTotalPromptTokens();
    }

    public long getTotalCompletionTokens() {
        return store.getTotalCompletionTokens();
    }

    public long getTotalEnergyJ() {
        return store.getTotalEnergyJ();
    }

    public double getTotalCostUsd() {
        return store.getTotalCostUsd();
    }

    public void reset() {
        store.reset();
    }
}
