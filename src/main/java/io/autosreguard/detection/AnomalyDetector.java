package io.autosreguard.detection;

import java.time.Instant;
import java.util.Optional;

/**
 * Streaming anomaly detector for a single time series.
 *
 * <p>Detection must be faster than threshold-based alerting, which means reacting to
 * "abnormal for this signal at this time" rather than "above a number someone picked two
 * years ago". Implementations learn a baseline online.
 */
public interface AnomalyDetector {

    /**
     * Consumes one sample and returns an anomaly if the sample deviates from the learned baseline.
     * Implementations decide whether anomalous samples update the baseline (usually they should not,
     * so an ongoing incident does not become the "new normal").
     */
    Optional<Anomaly> observe(Instant at, double value);

    /** Current baseline estimate, for dashboards and diagnosis evidence. */
    double baseline();
}
