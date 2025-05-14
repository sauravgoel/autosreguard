package io.autosreguard.capacity;

import io.autosreguard.core.Preconditions;

import java.time.Duration;
import java.util.Objects;

/**
 * Capacity characteristics of one serving replica on a given accelerator type.
 *
 * <p>{@code warmup} is the most under-appreciated number in inference capacity planning.
 * Loading tens of gigabytes of weights into VRAM, compiling kernels and warming caches can
 * take minutes. Reactive autoscaling triggered by a spike pays that full delay while users
 * wait; predictive scaling hides it by starting early.
 *
 * @param name                    accelerator/replica profile name
 * @param vramGb                  device memory available to the replica
 * @param sustainableTokensPerSec tokens/sec one replica sustains while meeting latency SLOs
 *                                (measured by load test, NOT the vendor's peak figure)
 * @param warmup                  time from "scale up" to "serving traffic"
 */
public record GpuProfile(String name, double vramGb, double sustainableTokensPerSec, Duration warmup) {

    public GpuProfile {
        Objects.requireNonNull(name, "name");
        Preconditions.checkPositive(vramGb, "vramGb");
        Preconditions.checkPositive(sustainableTokensPerSec, "sustainableTokensPerSec");
        Objects.requireNonNull(warmup, "warmup");
        if (warmup.isNegative()) {
            throw new IllegalArgumentException("warmup must be >= 0");
        }
    }
}
