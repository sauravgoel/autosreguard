package io.autosreguard.diagnosis;

import io.autosreguard.detection.Anomaly;
import io.autosreguard.detection.Signals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootCauseAnalyzerTest {

    private static final Instant ONSET = Instant.parse("2026-04-17T14:20:30Z");

    private ServiceGraph graph;
    private ChangeLog changes;
    private RootCauseAnalyzer rca;

    @BeforeEach
    void setUp() {
        graph = new ServiceGraph()
                .dependsOn("api-gateway", "inference-router")
                .dependsOn("inference-router", "pool-llama-70b")
                .dependsOn("inference-router", "pool-llama-8b");
        changes = new ChangeLog(100);
        rca = new RootCauseAnalyzer(graph, changes, Duration.ofMinutes(30), Duration.ofMinutes(10));
    }

    private static Anomaly errors(String component, Instant at) {
        return new Anomaly(component, Signals.ERROR_RATE, 0.05, 0.002, 12, 0.99, at);
    }

    private static ChangeEvent deploy(String id, String component, Instant at, String author) {
        return new ChangeEvent(id, ChangeEvent.Kind.DEPLOYMENT, component, "v42", "v43", at, author);
    }

    @Test
    void blamesTheRecentDeployOnTheRootComponentNotItsCallers() {
        changes.record(deploy("deploy-8812", "pool-llama-70b", ONSET.minusSeconds(30), "ci"));
        List<Hypothesis> hs = rca.analyze(List.of(
                errors("api-gateway", ONSET), errors("inference-router", ONSET), errors("pool-llama-70b", ONSET)));

        Hypothesis top = hs.get(0);
        assertEquals(Hypothesis.Kind.BAD_CHANGE, top.kind());
        assertEquals("pool-llama-70b", top.subject());
        assertEquals("deploy-8812", top.change().orElseThrow().id());
        assertTrue(top.confidence() > 0.85, "a tight, root-level correlation should clear the auto threshold");
        assertFalse(top.evidence().isEmpty(), "every hypothesis must explain itself");
    }

    @Test
    void olderChangesAreLessSuspicious() {
        changes.record(deploy("fresh", "pool-llama-70b", ONSET.minusSeconds(30), "ci"));
        double fresh = rca.analyze(List.of(errors("pool-llama-70b", ONSET))).get(0).confidence();

        setUp();
        changes.record(deploy("stale", "pool-llama-70b", ONSET.minus(Duration.ofMinutes(25)), "ci"));
        double stale = rca.analyze(List.of(errors("pool-llama-70b", ONSET))).get(0).confidence();
        assertTrue(fresh > stale);
    }

    @Test
    void ignoresOurOwnAutomatedChanges() {
        changes.record(deploy("rollback-1", "pool-llama-70b", ONSET.minusSeconds(30), ChangeEvent.AUTOMATION_AUTHOR));
        List<Hypothesis> hs = rca.analyze(List.of(errors("pool-llama-70b", ONSET)));
        assertTrue(hs.stream().noneMatch(h -> h.kind() == Hypothesis.Kind.BAD_CHANGE));
    }

    @Test
    void rootWithoutACorrelatedChangeIsAComponentFault() {
        List<Hypothesis> hs = rca.analyze(List.of(errors("inference-router", ONSET), errors("pool-llama-8b", ONSET)));
        Hypothesis top = hs.get(0);
        assertEquals(Hypothesis.Kind.COMPONENT_FAULT, top.kind());
        assertEquals("pool-llama-8b", top.subject());
    }

    @Test
    void gpuSignalsBecomeHardwareHypotheses() {
        Anomaly ecc = new Anomaly("gpu-node-05", Signals.GPU_ECC_ERRORS, 0.1, 1.0, -9, 0.97, ONSET);
        Hypothesis top = rca.analyze(List.of(ecc)).get(0);
        assertEquals(Hypothesis.Kind.HARDWARE_DEGRADATION, top.kind());
        assertEquals("gpu-node-05", top.subject());
    }

    @Test
    void noAnomaliesNoHypotheses() {
        assertTrue(rca.analyze(List.of()).isEmpty());
    }

    @Test
    void graphComputesTransitiveDependenciesAndDependents() {
        assertTrue(graph.transitiveDependencies("api-gateway").contains("pool-llama-70b"));
        assertTrue(graph.dependents("pool-llama-70b").contains("inference-router"));
    }
}
