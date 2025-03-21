package io.autosreguard.slo;

import io.autosreguard.core.Preconditions;
import io.autosreguard.core.Segment;

import java.util.Objects;

/**
 * A service-level objective scoped to one user segment.
 *
 * <p>Note that there is intentionally no "global" SLO type. You can still compute a global
 * number for reporting, but objectives and alerts are always attached to a segment,
 * because a single number across your whole user base will hide the users who matter most.
 *
 * @param name              stable identifier, used in alerts and metrics labels
 * @param segment           the user segment this objective protects
 * @param sli               which indicator the objective is built on
 * @param target            objective as a fraction, e.g. {@code 0.999}
 * @param latencyThresholdMs threshold for latency SLIs; ignored for {@link SliKind#AVAILABILITY}
 */
public record SloDefinition(String name, Segment segment, SliKind sli, double target, double latencyThresholdMs) {

    public SloDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(segment, "segment");
        Objects.requireNonNull(sli, "sli");
        Preconditions.checkFraction(target, "target");
        if (sli != SliKind.AVAILABILITY) {
            Preconditions.checkPositive(latencyThresholdMs, "latencyThresholdMs");
        }
    }

    public static SloDefinition availability(String name, Segment segment, double target) {
        return new SloDefinition(name, segment, SliKind.AVAILABILITY, target, 0);
    }

    public static SloDefinition ttft(String name, Segment segment, double target, double thresholdMs) {
        return new SloDefinition(name, segment, SliKind.TTFT, target, thresholdMs);
    }

    public static SloDefinition tpot(String name, Segment segment, double target, double thresholdMs) {
        return new SloDefinition(name, segment, SliKind.TPOT, target, thresholdMs);
    }

    /** The error budget as a fraction of events: {@code 1 - target}. */
    public double errorBudget() {
        return 1.0 - target;
    }

    /** Whether this SLO applies to the given outcome at all (segment scoping). */
    public boolean appliesTo(InferenceOutcome o) {
        return o.segment() == segment;
    }

    /**
     * Classifies an outcome as good or bad under this SLI.
     * A failed request is always bad, regardless of SLI kind: a fast error is not good latency.
     */
    public boolean isGood(InferenceOutcome o) {
        if (!o.success()) {
            return false;
        }
        return switch (sli) {
            case AVAILABILITY -> true;
            case TTFT -> o.ttftMillis() >= 0 && o.ttftMillis() <= latencyThresholdMs;
            case TPOT -> o.tpotMillis() >= 0 && o.tpotMillis() <= latencyThresholdMs;
        };
    }
}
