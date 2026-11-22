package org.opentron.backend.power;

import java.util.Optional;

/**
 * A source of measured instantaneous power draw (in watts).
 */
public interface PowerSampler {

    /** Short machine-readable identifier surfaced to the API/UI as this reading's provenance. */
    String sourceName();

    /**
     * Attempts one power reading. Returns Optional.empty() if this
     * sampler cannot produce a real reading right now.
     */
    Optional<Double> samplePowerWatts();
}
