package io.autosreguard.slo;

/**
 * The kind of service-level indicator an SLO is built on.
 *
 * <p>Every SLI is expressed as a ratio of <em>good events</em> to <em>valid events</em>.
 * This uniform shape is what lets the same error-budget and burn-rate math apply to
 * availability and latency alike.
 */
public enum SliKind {

    /** Good = request succeeded. */
    AVAILABILITY,

    /**
     * Good = request succeeded AND time-to-first-token is under threshold.
     * Critical for interactive/streaming experiences.
     */
    TTFT,

    /**
     * Good = request succeeded AND time-per-output-token is under threshold.
     * Governs perceived generation speed and batch throughput.
     */
    TPOT
}
