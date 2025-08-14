package io.autosreguard.diagnosis;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A candidate root cause with evidence and a confidence score.
 *
 * <p>Evidence strings are not decoration: they are what the on-call engineer reads when the
 * system escalates, and what the audit log stores to make every automated action explainable.
 *
 * @param kind       class of cause
 * @param subject    the component believed to be at fault
 * @param change     the implicated change, if the cause is a change
 * @param confidence 0..1
 * @param evidence   human-readable reasoning chain
 */
public record Hypothesis(Kind kind, String subject, Optional<ChangeEvent> change, double confidence,
                         List<String> evidence) {

    public Hypothesis {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(change, "change");
        evidence = List.copyOf(evidence);
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be in [0,1]");
        }
    }

    public enum Kind {
        /** A recent deployment, config change, or model rollout. */
        BAD_CHANGE,
        /** A component failing on its own, propagating to its dependents. */
        COMPONENT_FAULT,
        /** Demand exceeds provisioned capacity. */
        CAPACITY_EXHAUSTION,
        /** Accelerator hardware degradation (ECC errors, thermal throttling). */
        HARDWARE_DEGRADATION
    }
}
