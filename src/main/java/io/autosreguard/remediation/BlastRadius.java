package io.autosreguard.remediation;

/**
 * How much of the system an action can affect if it's wrong.
 *
 * <p>Autonomy thresholds scale with blast radius: restarting a pod on 0.85 confidence is
 * fine; shifting an entire region's traffic on 0.85 confidence is not.
 */
public enum BlastRadius {
    /** One replica or node. */
    LOW(0.0),
    /** One service or model pool. */
    MEDIUM(0.03),
    /** A region, or many services. */
    HIGH(0.07);

    private final double extraConfidenceRequired;

    BlastRadius(double extraConfidenceRequired) {
        this.extraConfidenceRequired = extraConfidenceRequired;
    }

    /** Added to the base auto-remediation threshold for actions of this radius. */
    public double extraConfidenceRequired() {
        return extraConfidenceRequired;
    }
}
