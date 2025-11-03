package io.autosreguard.remediation;

/**
 * Read-only view of current fleet state the planner needs to build concrete actions
 * (e.g. "scale from 12 to 18" requires knowing it's 12 now).
 */
public interface FleetState {

    int replicas(String pool);

    /** Current load-shedding fraction for a service, 0..1. */
    double sheddingFraction(String service);

    int maxReplicas(String pool);
}
