package org.opentron.backend.power;

import org.opentron.backend.telemetry.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Periodically polls the available PowerSamplers (real CPU-package RAPL
 * counters, real NVIDIA GPU draw via nvidia-smi) and integrates whatever
 * real readings come back into accumulated energy (joules), recorded
 * through TelemetryService.addEnergyJ().
 *
 * If no sampler produces a real reading -- e.g. Windows without an
 * RAPL-equivalent wired up, a VM/container with sysfs power counters
 * hidden, or a machine with no NVIDIA GPU -- this service reports itself
 * as unavailable. Callers (TelemetryController) must check isAvailable() and return
 * "unavailable" response instead.
 */
@Service
public class PowerMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(PowerMonitorService.class);
    private static final long SAMPLE_INTERVAL_MS = 15_000;
    private static final int MAX_SAMPLES_KEPT = 60; // ~15 min of history at 15s cadence

    private final List<PowerSampler> samplers;
    private final TelemetryService telemetryService;

    private final Deque<Sample> recentSamples = new ArrayDeque<>();
    private volatile boolean everMeasured = false;
    private volatile List<String> activeSources = new ArrayList<>();

    public record Sample(Instant timestamp, double powerWatts, List<String> sources) {}

    public PowerMonitorService(List<PowerSampler> samplers, TelemetryService telemetryService) {
        this.samplers = samplers;
        this.telemetryService = telemetryService;
    }

    @Scheduled(fixedRate = SAMPLE_INTERVAL_MS)
    public void sampleAndAccumulate() {
        double totalWatts = 0.0;
        List<String> sourcesThisSample = new ArrayList<>();

        for (PowerSampler sampler : samplers) {
            try {
                Optional<Double> reading = sampler.samplePowerWatts();
                if (reading.isPresent()) {
                    totalWatts += reading.get();
                    sourcesThisSample.add(sampler.sourceName());
                }
            } catch (Exception e) {
                logger.debug("Power sampler {} failed: {}", sampler.sourceName(), e.toString());
            }
        }

        synchronized (this) {
            activeSources = sourcesThisSample;
            if (sourcesThisSample.isEmpty()) {
                return; // no reading this cycle
            }
            everMeasured = true;
            recentSamples.addLast(new Sample(Instant.now(), totalWatts, sourcesThisSample));
            while (recentSamples.size() > MAX_SAMPLES_KEPT) {
                recentSamples.removeFirst();
            }
        }

        long energyJThisInterval = Math.round(totalWatts * (SAMPLE_INTERVAL_MS / 1000.0));
        if (energyJThisInterval > 0) {
            telemetryService.addEnergyJ(energyJThisInterval);
        }
    }

    public synchronized boolean isAvailable() {
        return everMeasured && !recentSamples.isEmpty();
    }

    public synchronized List<String> activeSources() {
        return new ArrayList<>(activeSources);
    }

    public synchronized List<Sample> recentSamples() {
        return new ArrayList<>(recentSamples);
    }

    public synchronized Optional<Double> currentPowerWatts() {
        if (recentSamples.isEmpty()) return Optional.empty();
        return Optional.of(recentSamples.getLast().powerWatts());
    }

    public synchronized Optional<Double> avgPowerWatts() {
        if (recentSamples.isEmpty()) return Optional.empty();
        double sum = 0;
        for (Sample s : recentSamples) sum += s.powerWatts();
        return Optional.of(sum / recentSamples.size());
    }
}
