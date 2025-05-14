package io.autosreguard.capacity;

import io.autosreguard.core.Preconditions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Places model replicas onto GPU nodes using Best-Fit Decreasing with reliability constraints.
 *
 * <h2>Why Best-Fit Decreasing?</h2>
 * Bin packing is NP-hard, but BFD is a well-known heuristic with a proven worst-case bound
 * of about 11/9 of optimal, runs in O(n*m), and produces placements humans can reason about.
 * Placing the biggest workloads first avoids the classic failure where a large model has no
 * node left with enough contiguous VRAM.
 *
 * <h2>Reliability constraints layered on top</h2>
 * <ul>
 *   <li><b>Anti-affinity:</b> two replicas of one model group never share a node.</li>
 *   <li><b>Health floor:</b> nodes below {@code minHealth} receive no new work.</li>
 *   <li><b>Headroom:</b> each node keeps {@code headroomFraction} free to absorb KV-cache growth;
 *       filling GPUs to 100% invites OOM cascades across co-located workloads.</li>
 * </ul>
 */
public final class BinPacker {

    private final double minHealth;
    private final double headroomFraction;

    public BinPacker(double minHealth, double headroomFraction) {
        this.minHealth = Preconditions.checkUnitInterval(minHealth, "minHealth");
        if (!(headroomFraction >= 0 && headroomFraction < 1)) {
            throw new IllegalArgumentException("headroomFraction must be in [0,1)");
        }
        this.headroomFraction = headroomFraction;
    }

    public PackingResult pack(List<Workload> workloads, List<NodeCapacity> nodes) {
        // Healthy nodes only, with usable capacity net of headroom.
        Map<String, Double> free = new LinkedHashMap<>();
        Map<String, Double> usable = new HashMap<>();
        for (NodeCapacity n : nodes) {
            if (n.healthScore() >= minHealth) {
                double cap = n.capacityGb() * (1 - headroomFraction);
                free.put(n.nodeId(), cap);
                usable.put(n.nodeId(), cap);
            }
        }
        Map<String, Set<String>> groupsOnNode = new HashMap<>();
        Map<String, String> placements = new LinkedHashMap<>();
        List<Workload> unplaced = new ArrayList<>();

        List<Workload> sorted = new ArrayList<>(workloads);
        sorted.sort(Comparator.comparingDouble(Workload::vramGb).reversed().thenComparing(Workload::id));

        for (Workload w : sorted) {
            String best = null;
            double bestLeftover = Double.MAX_VALUE;
            for (Map.Entry<String, Double> e : free.entrySet()) {
                double leftover = e.getValue() - w.vramGb();
                boolean groupConflict = groupsOnNode.getOrDefault(e.getKey(), Set.of()).contains(w.replicaGroup());
                if (leftover >= 0 && !groupConflict && leftover < bestLeftover) {
                    best = e.getKey();
                    bestLeftover = leftover;
                }
            }
            if (best == null) {
                unplaced.add(w);
            } else {
                free.put(best, bestLeftover);
                groupsOnNode.computeIfAbsent(best, k -> new HashSet<>()).add(w.replicaGroup());
                placements.put(w.id(), best);
            }
        }

        double used = 0;
        double total = 0;
        int nodesUsed = 0;
        for (Map.Entry<String, Double> e : usable.entrySet()) {
            double u = e.getValue() - free.get(e.getKey());
            if (u > 0) {
                nodesUsed++;
                used += u;
                total += e.getValue();
            }
        }
        double utilization = total == 0 ? 0 : used / total;
        return new PackingResult(placements, unplaced, nodesUsed, utilization);
    }

    /**
     * @param placements  workload id -> node id
     * @param unplaced    workloads that could not be placed under the constraints (alert on this!)
     * @param nodesUsed   nodes hosting at least one workload
     * @param utilization VRAM utilization across used nodes, net of headroom
     */
    public record PackingResult(Map<String, String> placements, List<Workload> unplaced,
                                int nodesUsed, double utilization) {
        public PackingResult {
            placements = Map.copyOf(placements);
            unplaced = List.copyOf(unplaced);
        }

        public boolean complete() {
            return unplaced.isEmpty();
        }
    }
}
