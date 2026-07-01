package io.autosreguard.gpu;

import io.autosreguard.detection.Anomaly;
import io.autosreguard.detection.Signals;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuHealthScorerTest {

    private static final Instant T0 = Instant.parse("2026-04-17T16:00:00Z");

    private static GpuTelemetry healthy(String node, int minute) {
        return new GpuTelemetry(node, T0.plus(Duration.ofMinutes(minute)), 0, 0, 68, 0, 0.85, 0);
    }

    @Test
    void steadyHealthyTelemetryScoresPerfect() {
        GpuHealthScorer s = GpuHealthScorer.defaults();
        for (int m = 0; m < 30; m++) {
            s.observe(healthy("n1", m));
        }
        assertEquals(1.0, s.score("n1"), 1e-9);
        assertEquals(HealthGrade.HEALTHY, s.grade("n1"));
        assertTrue(s.toAnomaly("n1").isEmpty());
    }

    @Test
    void gradualThermalAndEccDeclineDegradesThenFails() {
        GpuHealthScorer s = GpuHealthScorer.defaults();
        HealthGrade worst = HealthGrade.HEALTHY;
        for (int m = 0; m < 20; m++) {
            s.observe(new GpuTelemetry("n1", T0.plus(Duration.ofMinutes(m)), m * 3L, 0, 75 + m, Math.min(1, m * 0.05),
                    0.9, 0));
            if (s.grade("n1").ordinal() > worst.ordinal()) {
                worst = s.grade("n1");
                if (worst == HealthGrade.DEGRADED) {
                    assertTrue(s.toAnomaly("n1").isEmpty(), "degraded is a dashboard signal, not an incident");
                }
            }
        }
        assertEquals(HealthGrade.FAILING, worst);
        Anomaly a = s.toAnomaly("n1").orElseThrow();
        assertTrue(Signals.isHardware(a.signal()));
    }

    @Test
    void uncorrectableEccIsAStickyHardFaultWithHighConfidence() {
        GpuHealthScorer s = GpuHealthScorer.defaults();
        s.observe(healthy("n2", 0));
        s.observe(new GpuTelemetry("n2", T0.plusSeconds(60), 0, 1, 68, 0, 0.85, 0));
        assertEquals(HealthGrade.FAILING, s.grade("n2"));
        Anomaly a = s.toAnomaly("n2").orElseThrow();
        assertEquals(Signals.GPU_ECC_ERRORS, a.signal());
        assertTrue(a.confidence() > 0.95);

        for (int m = 2; m < 60; m++) {
            s.observe(healthy("n2", m));
        }
        assertEquals(HealthGrade.FAILING, s.grade("n2"), "hardware does not heal itself");
        s.clearHardFault("n2");
        s.observe(healthy("n2", 61));
        assertEquals(HealthGrade.HEALTHY, s.grade("n2"));
    }

    @Test
    void unknownNodesAreHealthyAndSweepsListOffenders() {
        GpuHealthScorer s = GpuHealthScorer.defaults();
        assertEquals(1.0, s.score("never-seen"), 1e-9);
        s.observe(healthy("ok", 0));
        s.observe(new GpuTelemetry("bad", T0, 0, 1, 70, 0, 0.8, 0));
        assertEquals(List.of("bad"), s.nodesAtLeast(HealthGrade.DEGRADED));
    }
}
