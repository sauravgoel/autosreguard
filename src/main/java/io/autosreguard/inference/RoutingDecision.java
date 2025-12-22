package io.autosreguard.inference;

import java.util.List;

/**
 * Result of routing a request: either a chosen variant or a rejection with reasons.
 */
public sealed interface RoutingDecision permits RoutingDecision.Routed, RoutingDecision.Rejected {

    /**
     * @param variant            chosen variant
     * @param degraded           {@code true} if a better variant was eligible but skipped for
     *                           capacity/health reasons (graceful degradation happened)
     * @param estimatedLatencyMs estimate that was checked against the budget
     * @param notes              why higher-quality variants were skipped
     */
    record Routed(ModelVariant variant, boolean degraded, double estimatedLatencyMs, List<String> notes)
            implements RoutingDecision {
        public Routed {
            notes = List.copyOf(notes);
        }
    }

    /** No variant can serve the request within constraints. Return 429/503 with retry-after. */
    record Rejected(List<String> reasons) implements RoutingDecision {
        public Rejected {
            reasons = List.copyOf(reasons);
        }
    }
}
