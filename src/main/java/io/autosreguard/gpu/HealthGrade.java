package io.autosreguard.gpu;

/** Coarse health classification driving placement and draining decisions. */
public enum HealthGrade {
    /** Place new work freely. */
    HEALTHY,
    /** Serve existing work; avoid new placements; watch closely. */
    DEGRADED,
    /** Drain now: migrate workloads before the device fails and takes requests with it. */
    FAILING
}
