package io.autosreguard.demo;

import io.autosreguard.remediation.ActionExecutor;
import io.autosreguard.remediation.FleetState;
import io.autosreguard.remediation.RemediationAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory stand-in for real infrastructure: tracks versions, replica counts, shedding
 * levels and drained nodes, and applies remediation actions to them. The simulator reads this
 * state back to decide what "traffic" looks like, closing the loop.
 */
public final class SimulatedFleet implements ActionExecutor, FleetState {

    final Map<String, String> versions = new HashMap<>();
    final Map<String, Integer> replicas = new HashMap<>();
    final Map<String, Double> shedding = new HashMap<>();
    final Set<String> drained = new HashSet<>();
    final List<String> actionLog = new ArrayList<>();

    @Override
    public synchronized void execute(RemediationAction action) {
        switch (action) {
            case RemediationAction.Rollback r -> versions.put(r.target(), r.toVersion());
            case RemediationAction.RevertConfig rc -> versions.put(rc.target() + ":" + rc.key(), rc.goodValue());
            case RemediationAction.ShedLoad s -> shedding.put(s.target(), s.toFraction());
            case RemediationAction.ScaleReplicas s -> replicas.put(s.target(), s.toReplicas());
            case RemediationAction.DrainNode d -> {
                if (d.drain()) {
                    drained.add(d.target());
                } else {
                    drained.remove(d.target());
                }
            }
            case RemediationAction.ShiftTraffic t -> { /* no-op in this simulation */ }
        }
        actionLog.add(action.describe());
    }

    @Override
    public synchronized int replicas(String pool) {
        return replicas.getOrDefault(pool, 4);
    }

    @Override
    public synchronized double sheddingFraction(String service) {
        return shedding.getOrDefault(service, 0.0);
    }

    @Override
    public int maxReplicas(String pool) {
        return 64;
    }

    synchronized String version(String component) {
        return versions.get(component);
    }
}
