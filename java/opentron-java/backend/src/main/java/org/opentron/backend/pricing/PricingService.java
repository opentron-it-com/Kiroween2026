package org.opentron.backend.pricing;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDate;

/**
 * Single source of truth for provider $/token pricing on the backend.
 *
 * This table centralizes the numbers and records where each one came from and as of when, so a
 * stale price is something you can grep for.
 *
 * NOTE: this is a hardcoded table, not a live-fetched price feed 
 */
@Service
public class PricingService {

    public record Price(
        String modelKey,
        String label,
        double inputPerMillionUsd,
        double outputPerMillionUsd,
        String source,
        String asOf
    ) {
        public double costUsd(long inputTokens, long outputTokens) {
            return (inputTokens * inputPerMillionUsd + outputTokens * outputPerMillionUsd) / 1_000_000.0;
        }
    }

    private static final String ANTHROPIC_SOURCE = "https://www.anthropic.com/pricing#api";
    private static final String OPENAI_SOURCE = "https://openai.com/api/pricing/";
    private static final String GOOGLE_SOURCE = "https://ai.google.dev/gemini-api/docs/pricing";
    private static final String PRICES_AS_OF = "2026-08"; // update when re-checking rates
    private static final String CURRENT_MODEL_PRICES_AS_OF = "2026-11";

    // Ordered longest-prefix-first isn't required since we do exact-then-prefix
    // matching in priceFor(); insertion order here just keeps related models together.
    private final Map<String, Price> prices = buildTable();

    private static Map<String, Price> buildTable() {
        Map<String, Price> m = new LinkedHashMap<>();
        m.put("claude-opus-4.6", new Price("claude-opus-4.6", "Claude Opus 4.6", 15.00, 75.00, ANTHROPIC_SOURCE, PRICES_AS_OF));
        m.put("claude-fable-5-2", new Price("claude-fable-5-2", "Claude Fable 5.2", 8.00, 40.00, ANTHROPIC_SOURCE, CURRENT_MODEL_PRICES_AS_OF));
        m.put("claude-sonnet-5-5", new Price("claude-sonnet-5-5", "Claude Sonnet 5.5", 2.00, 10.00, ANTHROPIC_SOURCE, CURRENT_MODEL_PRICES_AS_OF));
        m.put("claude-haiku-5-5", new Price("claude-haiku-5-5", "Claude Haiku 5.5", 0.10, 0.50, ANTHROPIC_SOURCE, CURRENT_MODEL_PRICES_AS_OF));
        m.put("claude-sonnet-5", new Price("claude-sonnet-5", "Claude Sonnet 5", 3.00, 15.00, ANTHROPIC_SOURCE, PRICES_AS_OF));
        m.put("claude-sonnet", new Price("claude-sonnet", "Claude Sonnet", 3.00, 15.00, ANTHROPIC_SOURCE, PRICES_AS_OF));
        m.put("claude-opus", new Price("claude-opus", "Claude Opus", 15.00, 75.00, ANTHROPIC_SOURCE, PRICES_AS_OF));
        m.put("claude-haiku", new Price("claude-haiku", "Claude Haiku", 0.80, 4.00, ANTHROPIC_SOURCE, PRICES_AS_OF));

        m.put("gpt-5.3", new Price("gpt-5.3", "GPT-5.3", 2.00, 10.00, OPENAI_SOURCE, PRICES_AS_OF));
        m.put("gpt-4o", new Price("gpt-4o", "GPT-4o", 5.00, 15.00, OPENAI_SOURCE, PRICES_AS_OF));
        m.put("gpt-4-turbo", new Price("gpt-4-turbo", "GPT-4 Turbo", 10.00, 30.00, OPENAI_SOURCE, PRICES_AS_OF));
        m.put("gpt-4", new Price("gpt-4", "GPT-4", 30.00, 60.00, OPENAI_SOURCE, PRICES_AS_OF));

        m.put("gemini-4-carbon", gemini4Price("gemini-4-carbon", "Gemini 4 Carbon"));
        m.put("gemini-4-argon", gemini4Price("gemini-4-argon", "Gemini 4 Argon"));
        m.put("gemini-4-neon", new Price("gemini-4-neon", "Gemini 4 Neon", 3.00, 15.00, GOOGLE_SOURCE, CURRENT_MODEL_PRICES_AS_OF));
        m.put("gemini-4-hydrogen", new Price("gemini-4-hydrogen", "Gemini 4 Hydrogen", 1.50, 7.50, GOOGLE_SOURCE, CURRENT_MODEL_PRICES_AS_OF));
        m.put("gemini-3.1-pro", new Price("gemini-3.1-pro", "Gemini 3.1 Pro", 2.00, 12.00, GOOGLE_SOURCE, PRICES_AS_OF));
        m.put("gemini-3.6-flash", new Price("gemini-3.6-flash", "Gemini 3.6 Flash", 0.075, 0.30, GOOGLE_SOURCE, PRICES_AS_OF));
        m.put("gemini-2.5-flash", new Price("gemini-2.5-flash", "Gemini 2.5 Flash", 0.075, 0.30, GOOGLE_SOURCE, PRICES_AS_OF));

        return m;
    }

    private static Price gemini4Price(String modelKey, String label) {
        boolean decemberRates = !LocalDate.now().isBefore(LocalDate.of(2026, 12, 1));
        double inputPrice = decemberRates ? 4.00 : 2.00;
        double outputPrice = decemberRates ? 20.00 : 10.00;
        String asOf = decemberRates ? "2026-12" : CURRENT_MODEL_PRICES_AS_OF;
        return new Price(modelKey, label, inputPrice, outputPrice, GOOGLE_SOURCE, asOf);
    }

    /**
     * Looks up pricing for a model, trying an exact match first then the
     * longest registered prefix (so "claude-sonnet-5-20260801" still resolves
     * to "claude-sonnet-5"). Returns empty when the model isn't in the table.
     */
    public Optional<Price> priceFor(String model) {
        if (model == null || model.isBlank()) return Optional.empty();
        String lower = model.toLowerCase();

        Price exact = prices.get(lower);
        if (exact != null) return Optional.of(exact);

        String bestKey = null;
        for (String key : prices.keySet()) {
            if (lower.startsWith(key) && (bestKey == null || key.length() > bestKey.length())) {
                bestKey = key;
            }
        }
        return bestKey != null ? Optional.of(prices.get(bestKey)) : Optional.empty();
    }

    /** Real cost for a real (model, inputTokens, outputTokens) triple. Empty if the model has no known price. */
    public Optional<Double> costUsd(String model, long inputTokens, long outputTokens) {
        return priceFor(model).map(p -> p.costUsd(inputTokens, outputTokens));
    }

    /** All known counterfactual comparison prices, e.g. for "what would this have cost on Claude/GPT/Gemini". */
    public Map<String, Price> allPrices() {
        return prices;
    }

    /**
     * Classifies a model name into a provider family, for grouping REAL
     * accrued cost/usage (see PersistentTelemetryStore.addProviderCost). 
     * This needs to recognize any model that might actually be requested, not just the
     * fixed set priceFor() has a rate for, so it stays a plain prefix check
     * rather than a lookup into `prices`. Returns null for a model that
     * isn't one of the tracked cloud families (e.g. a raw Ollama tag like
     * "llama3.2:3b").
     */
    public String providerGroupFor(String model) {
        if (model == null || model.isBlank()) return null;
        String lower = model.toLowerCase();
        if (lower.startsWith("openrouter/")) return "openrouter";
        if (lower.startsWith("claude-")) return "anthropic";
        if (lower.startsWith("gpt-") || lower.startsWith("gpt4") || lower.startsWith("o1-")
                || lower.startsWith("o3-") || lower.startsWith("o4-") || lower.startsWith("chatgpt-")) {
            return "openai";
        }
        if (lower.startsWith("gemini-")) return "google";
        return null;
    }

    /** Human-readable label for a providerGroupFor() key, for display. */
    public static String labelForProviderGroup(String group) {
        if (group == null) return "Unknown";
        return switch (group) {
            case "anthropic" -> "Anthropic";
            case "openai" -> "OpenAI";
            case "google" -> "Google";
            case "openrouter" -> "OpenRouter";
            default -> group.isBlank() ? "Unknown" : Character.toUpperCase(group.charAt(0)) + group.substring(1);
        };
    }
}
