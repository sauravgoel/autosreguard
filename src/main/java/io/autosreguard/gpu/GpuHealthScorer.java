package io.autosreguard.gpu;

import io.autosreguard.core.Preconditions;
import io.autosreguard.detection.Anomaly;
import io.autosreguard.detection.Signals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Continuous health scoring for accelerators.
 *
 * <h2>Why a score and not a binary up/down?</h2>
 * Sustained high utilization accelerates hardware degradation, and traditional availability
 * math does not account for gradual health decline. A GPU rarely goes from perfect to dead: it
 * throttles, runs hot, accumulates correctable ECC errors, then fails, often mid-request and
 * with a model's entire KV cache on it. A continuous score lets us migrate work off a device
 * <em>while it is still serving</em>.
 *
 * <h2>Scoring</h2>
 * Each node carries an exponentially decaying "penalty" accumulated from recent samples:
 * <ul>
 *   <li>uncorrectable ECC or XID errors: large immediate penalty (and forces FAILING)</li>
 *   <li>correctable ECC errors: small penalty per error, decaying</li>
 *   <li>temperature above the soft limit: penalty proportional to excess</li>
 *   <li>throttling: penalty proportional to throttle ratio</li>
 * </ul>
 * {@code score = exp(-penalty)} maps penalty onto (0,1]. The decay factor lets a node that had
 * one bad minute recover its score, while persistent problems keep it down.
 */
public final class GpuHealthScorer {

    private final double decay;
    private final double softTempLimitC;
    private final double degradedBelow;
    private final double failingBelow;

    private record NodeState(double penalty, boolean hardFault, GpuTelemetry last) {
    }

    private final Map<String, NodeState> nodes = new ConcurrentHashMap<>();

    /**
     * @param decay          per-sample penalty retention in (0,1); 0.9 = 10% forgotten per sample
     * @param softTempLimitC temperature above which penalties start
     * @param degradedBelow  score below which a node is DEGRADED
     * @param failingBelow   score below which a node is FAILING
     */
    public GpuHealthScorer(double decay, double softTempLimitC, double degradedBelow, double failingBelow) {
        this.decay = Preconditions.checkFraction(decay, "decay");
        this.softTempLimitC = Preconditions.checkPositive(softTempLimitC, "softTempLimitC");
        this.degradedBelow = Preconditions.checkFraction(degradedBelow, "degradedBelow");
        this.failingBelow = Preconditions.checkFraction(failingBelow, "failingBelow");
        if (failingBelow >= degradedBelow) {
            throw new IllegalArgumentException("failingBelow must be < degradedBelow");
        }
    }

    public static GpuHealthScorer defaults() {
        return new GpuHealthScorer(0.9, 83.0, 0.8, 0.5);
    }

    public void observe(GpuTelemetry t) {
        nodes.compute(t.nodeId(), (id, prev) -> {
            double p = prev == null ? 0 : prev.penalty() * decay;
            boolean hard = prev != null && prev.hardFault();
            p += 0.02 * t.eccCorrectableDelta();
            if (t.eccUncorrectableDelta() > 0 || t.xidErrorsDelta() > 0) {
                p += 2.0;
                hard = true; // sticky until a human clears it: hardware does not heal itself
            }
            p += Math.max(0, t.temperatureC() - softTempLimitC) * 0.03;
            p += t.throttleRatio() * 0.3;
            return new NodeState(p, hard, t);
        });
    }

    public double score(String nodeId) {
        NodeState s = nodes.get(nodeId);
        return s == null ? 1.0 : Math.exp(-s.penalty());
    }

    public HealthGrade grade(String nodeId) {
        NodeState s = nodes.get(nodeId);
        if (s == null) {
            return HealthGrade.HEALTHY;
        }
        double score = Math.exp(-s.penalty());
        if (s.hardFault() || score < failingBelow) {
            return HealthGrade.FAILING;
        }
        return score < degradedBelow ? HealthGrade.DEGRADED : HealthGrade.HEALTHY;
    }

    /** Clears a sticky hard fault after hardware replacement or validation. */
    public void clearHardFault(String nodeId) {
        nodes.computeIfPresent(nodeId, (id, s) -> new NodeState(0, false, s.last()));
    }

    /**
     * Converts a FAILING node into an {@link Anomaly} with a {@code gpu_} signal, so it flows
     * through the same diagnosis and remediation pipeline as every other signal and ends up
     * as a {@code HARDWARE_DEGRADATION} hypothesis with a drain action.
     */
    public Optional<Anomaly> toAnomaly(String nodeId) {
        NodeState s = nodes.get(nodeId);
        if (s == null || grade(nodeId) != HealthGrade.FAILING) {
            return Optional.empty();
        }
        GpuTelemetry t = s.last();
        String signal = t.eccUncorrectableDelta() > 0 || s.hardFault() ? Signals.GPU_ECC_ERRORS
                : t.throttleRatio() > 0.2 ? Signals.GPU_THROTTLE : Signals.GPU_TEMP_C;
        double score = score(nodeId);
        double confidence = s.hardFault() ? 0.97 : Math.min(0.95, 0.6 + (failingBelow - score));
        return Optional.of(new Anomaly(nodeId, signal, score, 1.0, -(1 - score) * 10, confidence, t.at()));
    }

    /** All nodes currently at or below the given grade, for dashboards and sweeps. */
    public List<String> nodesAtLeast(HealthGrade g) {
        List<String> out = new ArrayList<>();
        for (String id : nodes.keySet()) {
            if (grade(id).ordinal() >= g.ordinal()) {
                out.add(id);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    public String describe(String nodeId) {
        return String.format(Locale.ROOT, "%s score=%.2f grade=%s", nodeId, score(nodeId), grade(nodeId));
    }
}
