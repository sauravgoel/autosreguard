package io.autosreguard.remediation;

import io.autosreguard.core.Preconditions;

import java.util.Locale;
import java.util.Objects;

/**
 * A concrete, reversible action the remediation layer can take.
 *
 * <p>The sealed hierarchy enforces a hard design rule: <b>every automated action must be
 * reversible</b>. Each variant implements {@link #inverse()}, so "undo" is always one call
 * away and is recorded in the audit log alongside the original.
 *
 * <p>Actions are pure descriptions. Executing them is the job of an {@link ActionExecutor},
 * which keeps this module independent of Kubernetes, cloud APIs, or config systems.
 */
public sealed interface RemediationAction {

    /** The component this action operates on; used for cooldowns and flap detection. */
    String target();

    /** Stable short name of the action type, e.g. {@code rollback}. */
    String type();

    BlastRadius blastRadius();

    /** The action that undoes this one. */
    RemediationAction inverse();

    /** Human-readable description for notifications and audit. */
    String describe();

    /** Roll a component back from a bad version to the last good one. */
    record Rollback(String target, String fromVersion, String toVersion) implements RemediationAction {
        public Rollback {
            Objects.requireNonNull(target);
            Objects.requireNonNull(fromVersion);
            Objects.requireNonNull(toVersion);
        }

        @Override public String type() { return "rollback"; }
        @Override public BlastRadius blastRadius() { return BlastRadius.MEDIUM; }
        @Override public RemediationAction inverse() { return new Rollback(target, toVersion, fromVersion); }
        @Override public String describe() { return "roll back " + target + " " + fromVersion + " -> " + toVersion; }
    }

    /** Revert a configuration key to its previous value (configuration healing). */
    record RevertConfig(String target, String key, String badValue, String goodValue) implements RemediationAction {
        public RevertConfig {
            Objects.requireNonNull(target);
            Objects.requireNonNull(key);
            Objects.requireNonNull(badValue);
            Objects.requireNonNull(goodValue);
        }

        @Override public String type() { return "revert_config"; }
        @Override public BlastRadius blastRadius() { return BlastRadius.MEDIUM; }
        @Override public RemediationAction inverse() { return new RevertConfig(target, key, goodValue, badValue); }
        @Override public String describe() { return "revert " + target + " config " + key + ": " + badValue + " -> " + goodValue; }
    }

    /**
     * Shed a fraction of low-priority traffic to protect core user journeys.
     * {@code fraction} applies to the lowest-priority segment first.
     */
    record ShedLoad(String target, double fromFraction, double toFraction) implements RemediationAction {
        public ShedLoad {
            Objects.requireNonNull(target);
            Preconditions.checkUnitInterval(fromFraction, "fromFraction");
            Preconditions.checkUnitInterval(toFraction, "toFraction");
        }

        @Override public String type() { return "shed_load"; }
        @Override public BlastRadius blastRadius() { return toFraction > 0.5 ? BlastRadius.HIGH : BlastRadius.MEDIUM; }
        @Override public RemediationAction inverse() { return new ShedLoad(target, toFraction, fromFraction); }
        @Override public String describe() {
            return String.format(Locale.ROOT, "set load shedding on %s %.0f%% -> %.0f%%", target, fromFraction * 100, toFraction * 100);
        }
    }

    /** Inject emergency capacity (or remove it, as the inverse). */
    record ScaleReplicas(String target, int fromReplicas, int toReplicas) implements RemediationAction {
        public ScaleReplicas {
            Objects.requireNonNull(target);
            if (fromReplicas < 0 || toReplicas < 0) {
                throw new IllegalArgumentException("replica counts must be >= 0");
            }
        }

        @Override public String type() { return "scale"; }
        @Override public BlastRadius blastRadius() { return toReplicas < fromReplicas ? BlastRadius.MEDIUM : BlastRadius.LOW; }
        @Override public RemediationAction inverse() { return new ScaleReplicas(target, toReplicas, fromReplicas); }
        @Override public String describe() { return "scale " + target + " " + fromReplicas + " -> " + toReplicas + " replicas"; }
    }

    /** Cordon and drain a degrading GPU node, migrating workloads off it. */
    record DrainNode(String target, boolean drain) implements RemediationAction {
        public DrainNode {
            Objects.requireNonNull(target);
        }

        @Override public String type() { return drain ? "drain_node" : "uncordon_node"; }
        @Override public BlastRadius blastRadius() { return BlastRadius.LOW; }
        @Override public RemediationAction inverse() { return new DrainNode(target, !drain); }
        @Override public String describe() { return (drain ? "drain " : "uncordon ") + target; }
    }

    /** Shift a fraction of traffic from one region to another. */
    record ShiftTraffic(String target, String toRegion, double fraction) implements RemediationAction {
        public ShiftTraffic {
            Objects.requireNonNull(target);
            Objects.requireNonNull(toRegion);
            Preconditions.checkUnitInterval(fraction, "fraction");
        }

        @Override public String type() { return "shift_traffic"; }
        @Override public BlastRadius blastRadius() { return BlastRadius.HIGH; }
        @Override public RemediationAction inverse() { return new ShiftTraffic(toRegion, target, fraction); }
        @Override public String describe() {
            return String.format(Locale.ROOT, "shift %.0f%% of traffic %s -> %s", fraction * 100, target, toRegion);
        }
    }
}
