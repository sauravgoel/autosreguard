package io.autosreguard.diagnosis;

import io.autosreguard.detection.Anomaly;
import io.autosreguard.detection.Signals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Correlates anomalies across services to produce ranked root-cause hypotheses.
 *
 * <h2>Reasoning steps</h2>
 * <ol>
 *   <li><b>Topology</b>: an anomalous component whose own dependencies are all healthy is a
 *       <em>root candidate</em>. Anomalous components that depend on a root candidate are
 *       <em>symptoms</em>. This alone eliminates most of the noise in a cascading failure.</li>
 *   <li><b>Change correlation</b>: for each root candidate, look for changes shortly before
 *       anomaly onset. Temporal proximity decays exponentially with time constant {@code tau}.
 *       Changes already superseded (e.g. rolled back) and AutoSREGuard's own remediations are
 *       excluded: they are not what's live in production.</li>
 *   <li><b>Signal family</b>: hardware signals point to hardware; saturation signals with no
 *       change point to capacity; everything else is a generic component fault.</li>
 *   <li><b>Coverage</b>: a hypothesis that explains more of the anomalous components (via the
 *       dependency graph) is more likely.</li>
 * </ol>
 *
 * <p>This is heuristic causal inference, not a proof. That's why its output feeds a
 * confidence-tiered remediation engine instead of acting unconditionally.
 */
public final class RootCauseAnalyzer {

    private final ServiceGraph graph;
    private final ChangeLog changes;
    private final Duration correlationWindow;
    private final Duration tau;

    /**
     * @param graph             dependency graph
     * @param changes           production change log
     * @param correlationWindow how far before onset to look for changes (e.g. 30 min)
     * @param tau               proximity decay constant (e.g. 10 min): a change tau before onset
     *                          scores ~37% of one right at onset
     */
    public RootCauseAnalyzer(ServiceGraph graph, ChangeLog changes, Duration correlationWindow, Duration tau) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.correlationWindow = Objects.requireNonNull(correlationWindow, "correlationWindow");
        this.tau = Objects.requireNonNull(tau, "tau");
    }

    public List<Hypothesis> analyze(List<Anomaly> anomalies) {
        if (anomalies.isEmpty()) {
            return List.of();
        }
        Map<String, List<Anomaly>> byComponent = new LinkedHashMap<>();
        for (Anomaly a : anomalies) {
            byComponent.computeIfAbsent(a.component(), k -> new ArrayList<>()).add(a);
        }
        Set<String> anomalous = byComponent.keySet();
        Instant onset = anomalies.stream().map(Anomaly::detectedAt).min(Comparator.naturalOrder()).orElseThrow();

        // Step 1: separate root candidates from symptoms.
        Set<String> roots = new HashSet<>();
        for (String c : anomalous) {
            Set<String> deps = new HashSet<>(graph.transitiveDependencies(c));
            deps.retainAll(anomalous);
            if (deps.isEmpty()) {
                roots.add(c);
            }
        }

        List<Hypothesis> out = new ArrayList<>();
        for (String c : anomalous) {
            boolean isRoot = roots.contains(c);
            List<Anomaly> own = byComponent.get(c);
            double strength = own.stream().mapToDouble(Anomaly::confidence).max().orElse(0);

            Set<String> explained = new HashSet<>(graph.dependents(c));
            explained.retainAll(anomalous);
            double coverage = (1.0 + explained.size()) / anomalous.size();
            // Symptoms can still carry a change hypothesis, just a much weaker one.
            double rootFactor = isRoot ? 1.0 : 0.5;

            List<String> baseEvidence = new ArrayList<>();
            own.forEach(a -> baseEvidence.add("anomaly: " + a.describe()));
            baseEvidence.add(isRoot
                    ? "topology: no anomalous dependencies -> root candidate"
                    : "topology: has anomalous dependencies -> likely symptom");
            if (!explained.isEmpty()) {
                baseEvidence.add("topology: explains anomalous dependents " + explained);
            }

            // Step 2: change correlation (slight slack after onset for clock skew between systems).
            Instant windowEnd = onset.plus(Duration.ofSeconds(30));
            List<ChangeEvent> recent = changes.between(windowEnd, correlationWindow.plusSeconds(30));
            boolean changeFound = false;
            for (ChangeEvent ch : recent) {
                if (!ch.component().equals(c) || ch.isAutomated() || isSuperseded(ch, recent)) {
                    continue;
                }
                changeFound = true;
                double lagMs = Math.max(0, Duration.between(ch.at(), onset).toMillis());
                double proximity = Math.exp(-lagMs / tau.toMillis());
                double conf = clamp((0.35 + 0.45 * proximity + 0.2 * coverage) * strength * rootFactor);
                List<String> ev = new ArrayList<>(baseEvidence);
                ev.add(String.format(Locale.ROOT, "change: %s %s %s->%s by %s, %ds before onset (proximity %.2f)",
                        ch.kind(), ch.id(), ch.fromValue(), ch.toValue(), ch.author(),
                        (long) (lagMs / 1000), proximity));
                out.add(new Hypothesis(Hypothesis.Kind.BAD_CHANGE, c, Optional.of(ch), conf, ev));
            }

            // Step 3: signal families.
            boolean hardware = own.stream().anyMatch(a -> Signals.isHardware(a.signal()));
            boolean saturation = own.stream().anyMatch(a -> Signals.isSaturation(a.signal()));
            if (hardware) {
                out.add(new Hypothesis(Hypothesis.Kind.HARDWARE_DEGRADATION, c, Optional.empty(),
                        clamp(strength * (0.7 + 0.3 * coverage) * rootFactor), withNote(baseEvidence,
                        "signals: accelerator hardware metrics anomalous")));
            } else if (saturation) {
                out.add(new Hypothesis(Hypothesis.Kind.CAPACITY_EXHAUSTION, c, Optional.empty(),
                        clamp(strength * (0.55 + 0.3 * coverage) * rootFactor), withNote(baseEvidence,
                        "signals: saturation metrics anomalous")));
            } else if (isRoot && !changeFound) {
                out.add(new Hypothesis(Hypothesis.Kind.COMPONENT_FAULT, c, Optional.empty(),
                        clamp(strength * (0.4 + 0.3 * coverage)), withNote(baseEvidence,
                        "no correlated change found")));
            }
        }
        out.sort(Comparator.comparingDouble(Hypothesis::confidence).reversed());
        return out;
    }

    private static boolean isSuperseded(ChangeEvent ch, List<ChangeEvent> recent) {
        for (ChangeEvent later : recent) {
            if (ch.isSupersededBy(later)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> withNote(List<String> base, String note) {
        List<String> l = new ArrayList<>(base);
        l.add(note);
        return l;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
