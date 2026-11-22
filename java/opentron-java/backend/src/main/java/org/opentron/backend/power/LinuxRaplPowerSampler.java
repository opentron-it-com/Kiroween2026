package org.opentron.backend.power;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Reads real CPU package power via the Linux kernel's RAPL (Running Average
 * Power Limit) sysfs interface, e.g.
 * /sys/class/powercap/intel-rapl:0/energy_uj on Intel hosts with the
 * intel_rapl driver loaded (some distros restrict this file to root, in
 * which case this sampler is simply unavailable). An AMD equivalent path is
 * also probed for on newer kernels with the amd_energy driver.
 *
 * energy_uj is a monotonically increasing microjoule counter. Average power
 * between two successive reads is derived as:
 *   power_w = (energy_delta_uj / 1_000_000) / seconds_delta
 * The first call after startup (or after the counter wraps) has no prior
 * reading to diff against, so it returns Optional.empty().
 */
@Component
public class LinuxRaplPowerSampler implements PowerSampler {

    private static final Logger logger = LoggerFactory.getLogger(LinuxRaplPowerSampler.class);

    private static final String[] CANDIDATE_PATHS = {
        "/sys/class/powercap/intel-rapl:0/energy_uj",
        "/sys/class/powercap/intel-rapl-mmio:0/energy_uj",
        "/sys/class/powercap/amd_energy:0/energy_uj",
    };

    private Path activePath;
    private long lastEnergyUj = -1;
    private long lastTimestampNanos = -1;

    @Override
    public String sourceName() {
        return "linux-rapl-cpu-package";
    }

    @Override
    public synchronized Optional<Double> samplePowerWatts() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return Optional.empty();
        }

        Path path = resolvePath();
        if (path == null) {
            return Optional.empty();
        }

        long nowNanos = System.nanoTime();
        long energyUj;
        try {
            energyUj = Long.parseLong(Files.readString(path).trim());
        } catch (IOException | NumberFormatException e) {
            logger.debug("RAPL read failed at {}: {}", path, e.toString());
            return Optional.empty();
        }

        if (lastEnergyUj < 0) {
            // First read after startup: no delta yet, need a second sample
            // before a power figure can be derived.
            lastEnergyUj = energyUj;
            lastTimestampNanos = nowNanos;
            return Optional.empty();
        }

        double elapsedSeconds = (nowNanos - lastTimestampNanos) / 1e9;
        long deltaUj = energyUj - lastEnergyUj;
        lastEnergyUj = energyUj;
        lastTimestampNanos = nowNanos;

        if (elapsedSeconds <= 0) {
            return Optional.empty();
        }
        if (deltaUj < 0) {
            // Counter wrapped around max_energy_range_uj between reads --
            // skip this interval rather than report a bogus negative/huge
            // power figure.
            return Optional.empty();
        }

        double watts = (deltaUj / 1_000_000.0) / elapsedSeconds;
        return Optional.of(watts);
    }

    private Path resolvePath() {
        if (activePath != null) {
            return Files.isReadable(activePath) ? activePath : null;
        }
        for (String candidate : CANDIDATE_PATHS) {
            try {
                Path path = Paths.get(candidate);
                if (Files.isReadable(path)) {
                    activePath = path;
                    return path;
                }
            } catch (java.nio.file.InvalidPathException e) {
                // Not parseable as a path on this platform (e.g. the ":0"
                // segment against Windows drive-letter syntax) -- just not
                // a candidate here, not a fatal error.
                logger.debug("RAPL candidate path not valid on this platform: {}", candidate);
            }
        }
        return null;
    }
}
