package org.opentron.backend.controllers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.opentron.backend.pricing.PricingService;
import org.opentron.backend.telemetry.TelemetryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/savings")
public class SavingsController {

    private final TelemetryService telemetryService;
    private final PricingService pricingService;

    public SavingsController(TelemetryService telemetryService, PricingService pricingService) {
        this.telemetryService = telemetryService;
        this.pricingService = pricingService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> savings() {
        Map<String, Object> payload = new LinkedHashMap<>();

        long totalCalls = telemetryService.getTotalRequests();
        long totalPromptTokens = telemetryService.getTotalPromptTokens();
        long totalCompletionTokens = telemetryService.getTotalCompletionTokens();
        long totalTokens = totalPromptTokens + totalCompletionTokens;
        long totalEnergyJ = telemetryService.getTotalEnergyJ();
        double actualCostUsd = telemetryService.getTotalCostUsd();

        payload.put("total_calls", totalCalls);
        payload.put("total_prompt_tokens", totalPromptTokens);
        payload.put("total_completion_tokens", totalCompletionTokens);
        payload.put("total_tokens", totalTokens);
        payload.put("total_energy_j", totalEnergyJ);
        payload.put("local_cost", actualCostUsd);

        var providers = new ArrayList<Map<String, Object>>();
        for (PricingService.Price price : pricingService.allPrices().values()) {
            double cost = price.costUsd(totalPromptTokens, totalCompletionTokens);
            Map<String, Object> provider = new LinkedHashMap<>();
            provider.put("provider", price.modelKey());
            provider.put("label", price.label());
            provider.put("total_cost", cost);
            provider.put("input_per_million_usd", price.inputPerMillionUsd());
            provider.put("output_per_million_usd", price.outputPerMillionUsd());
            provider.put("price_source", price.source());
            provider.put("price_as_of", price.asOf());
            providers.add(provider);
        }

        payload.put("per_provider", providers);

        var realCosts = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Double> entry : telemetryService.getProviderCosts().entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("provider", entry.getKey());
            item.put("label", PricingService.labelForProviderGroup(entry.getKey()));
            item.put("total_cost", entry.getValue());
            realCosts.add(item);
        }
        realCosts.sort((a, b) -> Double.compare(
            (Double) b.get("total_cost"), (Double) a.get("total_cost")));
        payload.put("real_cost_by_provider", realCosts);

        payload.put("token_counting_version", 2);

        return Mono.just(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(payload));
    }
}
