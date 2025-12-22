package io.autosreguard.canary;

import java.util.List;

/**
 * @param decision what to do with the rollout
 * @param reasons  every check's result, so the verdict is auditable
 */
public record CanaryVerdict(Decision decision, List<String> reasons) {

    public CanaryVerdict {
        reasons = List.copyOf(reasons);
    }

    public enum Decision {
        /** Not enough data yet: keep the canary at its current traffic share. */
        CONTINUE,
        /** All checks pass with enough data: increase traffic share. */
        PROMOTE,
        /** A check failed: roll back automatically. */
        ROLLBACK
    }
}
