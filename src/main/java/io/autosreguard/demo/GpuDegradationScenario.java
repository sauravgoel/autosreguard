package io.autosreguard.demo;

import io.autosreguard.capacity.BinPacker;
import io.autosreguard.capacity.NodeCapacity;
import io.autosreguard.capacity.Workload;
import io.autosreguard.config.AutoSREGuardConfig;
import io.autosreguard.core.Clocks;
import io.autosreguard.diagnosis.ChangeLog;
import io.autosreguard.diagnosis.ServiceGraph;
import io.autosreguard.gpu.GpuHealthScorer;
import io.autosreguard.gpu.GpuTelemetry;
import io.autosreguard.remediation.AuditLog;
import io.autosreguard.remediation.Notifier;
import io.autosreguard.remediation.RemediationOutcome;
import io.autosreguard.runtime.ControlCycleReport;
import io.autosreguard.runtime.AutoSREGuard;
import io.autosreguard.runtime.AutoSREGuardFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.autosreguard.demo.BadDeployScenario.HHMMSS;

/**
 * Hardware health monitoring: a GPU degrades gradually (heat, throttling, rising ECC errors).
 * AutoSREGuard proposes a drain while the node is still serving, and the bin packer re-places
 * its replicas on healthy nodes with anti-affinity intact.
 */
final class GpuDegradationScenario implements Scenario {

    @Override
    public String name() {
        return "gpu-degradation";
    }

    @Override
    public void run(Console c) {
        c.banner("Scenario 3: Proactively draining degrading GPUs",
                "availability math ignores gradual hardware decline; health scores don't");

        Clocks.ManualClock clock = Clocks.manual(Instant.parse("2026-04-17T16:00:00Z"));
        SimulatedFleet fleet = new SimulatedFleet();
        Properties p = new Properties();
        p.setProperty("remediation.dry-run", "false");
        Notifier notifier = n -> c.event(HHMMSS.format(clock.instant()), "NOTIFY %-18s %s", n.urgency(), n.title());
        AutoSREGuard ig = AutoSREGuardFactory.create(AutoSREGuardConfig.of(p, Map.of()), clock, new ServiceGraph(),
                new ChangeLog(100), fleet, fleet, notifier, AuditLog.inMemory(100));
        GpuHealthScorer health = ig.gpuHealth();

        List<String> nodes = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            nodes.add(String.format("gpu-node-%02d", i));
        }
        c.section("Telemetry (1 sample/min). gpu-node-05 runs hot from 16:05; gpu-node-02 has a sudden ECC fault at 16:20");
        SplittableRandom rnd = new SplittableRandom(3);
        for (int minute = 0; minute <= 26; minute++) {
            Instant now = clock.instant();
            for (String n : nodes) {
                if (fleet.drained.contains(n)) {
                    // A drained node is cordoned and handed to hardware repair; it no longer
                    // reports into the serving fleet's health pipeline.
                    continue;
                }
                boolean sick = n.equals("gpu-node-05") && minute >= 5;
                int m = minute - 5;
                // gpu-node-05: slow thermal creep -> throttling -> rising correctable ECC.
                double temp = sick ? 72 + m * 1.3 : 68 + rnd.nextDouble(-2, 2);
                double throttle = sick && m >= 6 ? Math.min(1, (m - 6) * 0.05) : 0;
                long ecc = sick && m >= 4 ? (m - 3) * 3L : 0;
                // gpu-node-02: no warning at all, then an uncorrectable ECC error.
                long uncorrectable = n.equals("gpu-node-02") && minute == 20 ? 1 : 0;
                ig.observeGpu(new GpuTelemetry(n, now, ecc, uncorrectable, temp, throttle, 0.85, 0));
            }
            if (!fleet.drained.contains("gpu-node-05") && (minute % 2 == 0 || minute >= 10)) {
                c.event(HHMMSS.format(now), "%s", health.describe("gpu-node-05"));
            }
            if (minute == 20) {
                c.event(HHMMSS.format(now), "%s", health.describe("gpu-node-02"));
            }
            ControlCycleReport r = ig.runCycle();
            for (RemediationOutcome o : r.outcomes()) {
                c.event(HHMMSS.format(now), "DECIDE  %s -> %s: %s",
                        o.tier(), o.status(), o.action().map(a -> a.describe()).orElse("-"));
            }
            clock.advance(Duration.ofMinutes(1));
        }

        c.section("Re-packing replicas (best-fit decreasing, anti-affinity, 10% VRAM headroom)");
        List<Workload> workloads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            workloads.add(new Workload("llama-70b-" + i, "llama-70b", 70));
        }
        for (int i = 0; i < 2; i++) {
            workloads.add(new Workload("llama-8b-" + i, "llama-8b", 20));
        }
        for (int i = 0; i < 2; i++) {
            workloads.add(new Workload("embed-" + i, "embed", 8));
        }
        List<NodeCapacity> caps = new ArrayList<>();
        for (String n : nodes) {
            double score = fleet.drained.contains(n) ? 0.0 : health.score(n);
            caps.add(new NodeCapacity(n, 80, score));
        }
        BinPacker.PackingResult result = new BinPacker(0.8, 0.10).pack(workloads, caps);
        Map<String, List<String>> byNode = new TreeMap<>();
        result.placements().forEach((w, n) -> byNode.computeIfAbsent(n, k -> new ArrayList<>()).add(w));
        byNode.values().forEach(l -> l.sort(String::compareTo));
        byNode.forEach((n, ws) -> c.line("%s  %s", n, ws));
        c.line("nodes used=%d  VRAM utilization=%.0f%%  unplaced=%s  drained=%s",
                result.nodesUsed(), result.utilization() * 100,
                result.unplaced().stream().map(Workload::id).toList(), new TreeSet<>(fleet.drained));
        c.line("Anything left unplaced is exactly what the capacity planner has to buy or borrow next.");

        c.takeaway("Gradual decline got a suggested drain with a veto window; a hard fault got an immediate "
                + "automatic drain. Either way the workload moved before users saw errors.");
    }
}
