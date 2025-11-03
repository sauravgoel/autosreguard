package io.autosreguard.remediation;

import io.autosreguard.core.Preconditions;

import java.time.Duration;
import java.util.Objects;

/**
 * Maps (confidence, blast radius) to an {@link AutonomyTier}.
 *
 * <p>Setting these thresholds is one of the hardest problems in autonomous operations. Too
 * conservative and you have built an expensive alerting system; too aggressive and automated
 * actions cause more damage than they prevent. Start conservative, run in shadow mode
 * (see {@link io.autosreguard.shadow.ShadowEvaluator}), and lower thresholds only with evidence.
 *
 * @param autoThreshold    base confidence for {@link AutonomyTier#AUTO_REMEDIATE}
 * @param suggestThreshold confidence for {@link AutonomyTier#SUGGEST_AND_NOTIFY}
 * @param vetoWindow       how long a human has to veto a suggested action before it runs
 */
public record ConfidencePolicy(double autoThreshold, double suggestThreshold, Duration vetoWindow) {

    public ConfidencePolicy {
        Preconditions.checkUnitInterval(autoThreshold, "autoThreshold");
        Preconditions.checkUnitInterval(suggestThreshold, "suggestThreshold");
        Objects.requireNonNull(vetoWindow, "vetoWindow");
        if (suggestThreshold > autoThreshold) {
            throw new IllegalArgumentException("suggestThreshold must be <= autoThreshold");
        }
    }

    public static ConfidencePolicy defaults() {
        return new ConfidencePolicy(0.85, 0.6, Duration.ofMinutes(3));
    }

    public AutonomyTier tierFor(double confidence, BlastRadius radius) {
        if (confidence >= autoThreshold + radius.extraConfidenceRequired()) {
            return AutonomyTier.AUTO_REMEDIATE;
        }
        if (confidence >= suggestThreshold) {
            return AutonomyTier.SUGGEST_AND_NOTIFY;
        }
        return AutonomyTier.PAGE_AND_ASSIST;
    }
}
