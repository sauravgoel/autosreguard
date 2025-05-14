package io.autosreguard.capacity;

import java.util.List;

/**
 * The output of one capacity-planning cycle. Always carries a human-readable reason: an
 * autonomous system that cannot explain its scaling decisions cannot be trusted with them.
 *
 * @param currentReplicas replicas serving now
 * @param desiredReplicas replicas the planner wants
 * @param forecastPeak    upper-bound demand over the lookahead horizon (tokens/sec)
 * @param mode           whether this came from the predictive model or the reactive fallback
 * @param activeEvents   event signals influencing the forecast
 * @param reason         explanation for logs and audit
 */
public record ScalingDecision(
        int currentReplicas,
        int desiredReplicas,
        double forecastPeak,
        Mode mode,
        List<String> activeEvents,
        String reason) {

    public ScalingDecision {
        activeEvents = List.copyOf(activeEvents);
    }

    public enum Mode {
        /** Driven by the demand forecast, ahead of the curve. */
        PREDICTIVE,
        /** Forecaster not ready: fell back to current-load-based scaling. */
        REACTIVE
    }

    public int delta() {
        return desiredReplicas - currentReplicas;
    }

    public boolean isChange() {
        return delta() != 0;
    }
}
