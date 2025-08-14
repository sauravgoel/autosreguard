package io.autosreguard.gpu;

import io.autosreguard.core.Preconditions;

import java.time.Instant;
import java.util.Objects;

/**
 * One telemetry sample from an accelerator (as exported by DCGM, NVML, or a TPU runtime).
 *
 * @param nodeId                node/device id
 * @param at                    sample time
 * @param eccCorrectableDelta   correctable ECC errors since last sample (rising counts precede failures)
 * @param eccUncorrectableDelta uncorrectable ECC errors since last sample (any > 0 is serious)
 * @param temperatureC          core temperature
 * @param throttleRatio         fraction of the interval spent clock-throttled (thermal/power), 0..1
 * @param utilization           compute utilization 0..1
 * @param xidErrorsDelta        driver-reported fatal/critical error events since last sample
 */
public record GpuTelemetry(String nodeId, Instant at, long eccCorrectableDelta, long eccUncorrectableDelta,
                           double temperatureC, double throttleRatio, double utilization, long xidErrorsDelta) {

    public GpuTelemetry {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(at, "at");
        if (eccCorrectableDelta < 0 || eccUncorrectableDelta < 0 || xidErrorsDelta < 0) {
            throw new IllegalArgumentException("error deltas must be >= 0");
        }
        Preconditions.checkUnitInterval(throttleRatio, "throttleRatio");
        Preconditions.checkUnitInterval(utilization, "utilization");
    }
}
