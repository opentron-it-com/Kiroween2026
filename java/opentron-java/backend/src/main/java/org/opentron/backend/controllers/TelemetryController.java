package org.opentron.backend.controllers;

import org.opentron.backend.power.PowerMonitorService;
import org.opentron.backend.telemetry.TelemetryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/v1/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;
    private final PowerMonitorService powerMonitorService;

    public TelemetryController(TelemetryService telemetryService, PowerMonitorService powerMonitorService) {
        this.telemetryService = telemetryService;
        this.powerMonitorService = powerMonitorService;
    }

    @GetMapping("/energy")
    public ResponseEntity<Map<String, Object>> getEnergy() {
        long totalEnergyJ = telemetryService.getTotalEnergyJ();
        long totalTokens = telemetryService.getTotalTokens();
        double energyPerTokenJ = totalTokens > 0 ? (double) totalEnergyJ / totalTokens : 0.0;

        if (!powerMonitorService.isAvailable()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("available", false);
            body.put("source", "unavailable");
            body.put("message", "No real power measurement source could be read on this host " +
                "(checked: Linux RAPL CPU-package counters, NVIDIA GPU via nvidia-smi). " +
                "Energy and power figures are withheld rather than estimated.");
            body.put("total_energy_j", totalEnergyJ);
            body.put("energy_per_token_j", energyPerTokenJ);
            body.put("samples", List.of());
            return ResponseEntity.ok(body);
        }

        List<Map<String, Object>> samples = new ArrayList<>();
        for (PowerMonitorService.Sample s : powerMonitorService.recentSamples()) {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("timestamp", s.timestamp().toString());
            sample.put("power_w", s.powerWatts());
            sample.put("sources", s.sources());
            samples.add(sample);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", true);
        body.put("source", String.join("+", powerMonitorService.activeSources()));
        body.put("total_energy_j", totalEnergyJ);
        body.put("energy_per_token_j", energyPerTokenJ);
        body.put("avg_power_w", powerMonitorService.avgPowerWatts().orElse(0.0));
        body.put("current_power_w", powerMonitorService.currentPowerWatts().orElse(0.0));
        body.put("samples", samples);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getTelemetryStats() {
        return ResponseEntity.ok(Map.of(
            "total_requests", telemetryService.getTotalRequests(),
            "total_tokens", telemetryService.getTotalTokens()
        ));
    }

    @PostMapping("/track")
    public ResponseEntity<Map<String, String>> trackTelemetry(@RequestBody org.opentron.backend.dto.TelemetryTrackRequest data) {
        telemetryService.recordRequest();
        Long tokens = data.getTokens();
        if (tokens != null) {
            telemetryService.addTokens(tokens);
        }
        return ResponseEntity.ok(Map.of("status", "tracked"));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetTelemetry() {
        telemetryService.reset();
        return ResponseEntity.ok(Map.of("status", "reset"));
    }
}
