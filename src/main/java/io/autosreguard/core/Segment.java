package io.autosreguard.core;

/**
 * A user segment with its own reliability expectations.
 *
 * <p>The central lesson behind segmented SLOs: averaging availability across every user
 * mathematically hides the experience of the users who matter most. At 1B daily users,
 * 99.999% still leaves ~10,000 people failing at any instant, and if 2,000 of them work for
 * one enterprise customer, that customer is having a very bad day while every global
 * dashboard is green.
 *
 * <p>Each segment carries a <em>priority</em> used by load shedding (lower numbers are
 * protected longer) and a default availability target. Targets are defaults only; real
 * deployments override them in configuration.
 */
public enum Segment {

    /**
     * Contractual customers. Low churn tolerance, high contractual impact:
     * one bad incident can trigger SLA credits, audits or termination.
     */
    ENTERPRISE(0, 0.9995),

    /**
     * Programmatic API consumers. Often invisible to UI monitoring, but usually the
     * first signal of degradation because they retry aggressively and hit edge cases.
     */
    API(1, 0.999),

    /**
     * High-volume consumer traffic. Aggregate metrics work reasonably well here, and it is
     * the first segment we degrade (smaller model) or shed under pressure.
     */
    CONSUMER(2, 0.995);

    private final int sheddingPriority;
    private final double defaultAvailabilityTarget;

    Segment(int sheddingPriority, double defaultAvailabilityTarget) {
        this.sheddingPriority = sheddingPriority;
        this.defaultAvailabilityTarget = defaultAvailabilityTarget;
    }

    /** Lower value = shed later. ENTERPRISE traffic is the last to be dropped. */
    public int sheddingPriority() {
        return sheddingPriority;
    }

    /** Default availability objective, e.g. {@code 0.9995} for 99.95%. */
    public double defaultAvailabilityTarget() {
        return defaultAvailabilityTarget;
    }
}
