package io.autosreguard.detection;

import java.time.Instant;
import java.util.Objects;

/**
 * A detected deviation from normal behavior on one signal of one component.
 *
 * @param component  the service, model pool, or GPU node emitting the signal
 * @param signal     metric name, e.g. {@code error_rate}, {@code ttft_p99_ms}
 * @param observed   the value that triggered detection
 * @param expected   the detector's baseline estimate
 * @param zScore     standardized deviation; sign indicates direction
 * @param confidence 0..1, how sure the detector is this is a real anomaly (not noise)
 * @param detectedAt when it was detected
 */
public record Anomaly(
        String component,
        String signal,
        double observed,
        double expected,
        double zScore,
        double confidence,
        Instant detectedAt) {

    public Anomaly {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(detectedAt, "detectedAt");
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be in [0,1]");
        }
    }

    public String describe() {
        return String.format(java.util.Locale.ROOT, "%s/%s observed=%.3f expected=%.3f z=%.1f",
                component, signal, observed, expected, zScore);
    }
}
