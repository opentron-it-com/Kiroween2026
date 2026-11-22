package org.opentron.backend.power;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Reads real GPU power draw via `nvidia-smi`, when an NVIDIA GPU and driver
 * are present and the binary is on PATH. This captures GPU power only -- a
 * partial system reading, not total machine draw -- which is why
 * sourceName() is explicit about the "gpu-only" scope rather than implying
 * whole-system power.
 *
 * Availability is probed lazily on first use and cached: once nvidia-smi is
 * confirmed missing (IOException on process start), this sampler stops
 * trying to shell out on every poll.
 */
@Component
public class NvidiaSmiPowerSampler implements PowerSampler {

    private static final Logger logger = LoggerFactory.getLogger(NvidiaSmiPowerSampler.class);

    private volatile Boolean nvidiaSmiAvailable; // null = not yet probed

    @Override
    public String sourceName() {
        return "nvidia-smi-gpu-only";
    }

    @Override
    public Optional<Double> samplePowerWatts() {
        if (Boolean.FALSE.equals(nvidiaSmiAvailable)) {
            return Optional.empty();
        }

        try {
            Process process = new ProcessBuilder(
                "nvidia-smi", "--query-gpu=power.draw", "--format=csv,noheader,nounits"
            ).redirectErrorStream(false).start();

            double totalWatts = 0.0;
            int gpuCount = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        totalWatts += Double.parseDouble(line);
                        gpuCount++;
                    } catch (NumberFormatException ignored) {
                        // e.g. "[N/A]" on some cards/drivers -- skip that reading
                    }
                }
            }

            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0 || gpuCount == 0) {
                // Command ran, so the binary exists; just no usable reading
                // this cycle (e.g. no GPU, or transient driver hiccup).
                nvidiaSmiAvailable = true;
                return Optional.empty();
            }

            nvidiaSmiAvailable = true;
            return Optional.of(totalWatts);
        } catch (IOException e) {
            // nvidia-smi not installed / not on PATH -- stop probing.
            logger.debug("nvidia-smi unavailable: {}", e.toString());
            nvidiaSmiAvailable = false;
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
