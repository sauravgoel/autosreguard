package io.autosreguard.capacity;

import io.autosreguard.core.Clocks;
import io.autosreguard.forecast.DemandForecaster;
import io.autosreguard.forecast.Forecast;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityPlannerTest {

    private static final Instant NOW = Instant.parse("2026-04-17T09:40:00Z");
    // 1000 tok/s per replica at 70% target utilization -> 700 tok/s usable per replica.
    private static final GpuProfile H100 = new GpuProfile("h100", 80, 1000, Duration.ofMinutes(6));

    private static DemandForecaster forecaster(boolean ready, Function<Instant, Double> upper) {
        return new DemandForecaster() {
            @Override
            public void observe(Instant at, double value) {
            }

            @Override
            public Forecast forecast(Instant t) {
                double u = upper.apply(t);
                return new Forecast(t, u * 0.9, u * 0.8, u);
            }

            @Override
            public boolean isReady() {
                return ready;
            }
        };
    }

    private static CapacityPlanner planner(DemandForecaster f) {
        return CapacityPlanner.builder()
                .forecaster(f)
                .profile(H100)
                .clock(Clocks.manual(NOW))
                .lookahead(Duration.ofMinutes(15))
                .sampleStep(Duration.ofMinutes(5))
                .targetUtilization(0.7)
                .minReplicas(2)
                .maxReplicas(40)
                .maxScaleDownFraction(0.1)
                .build();
    }

    @Test
    void scalesAheadOfAForecastSpikeWithinWarmupPlusLookahead() {
        // Spike arrives 20 minutes from now: inside 6m warm-up + 15m lookahead.
        Instant spike = NOW.plus(Duration.ofMinutes(20));
        CapacityPlanner p = planner(forecaster(true, t -> t.isBefore(spike) ? 7_000.0 : 14_000.0));
        ScalingDecision d = p.plan(10, 7_000);
        assertEquals(ScalingDecision.Mode.PREDICTIVE, d.mode());
        assertEquals(20, d.desiredReplicas());
        assertTrue(d.isChange());
    }

    @Test
    void neverPlansBelowObservedDemandEvenIfTheForecastIsLow() {
        CapacityPlanner p = planner(forecaster(true, t -> 100.0));
        assertEquals(15, p.plan(15, 10_500).desiredReplicas());
    }

    @Test
    void scalesDownGraduallyToAvoidFlapping() {
        CapacityPlanner p = planner(forecaster(true, t -> 700.0));
        ScalingDecision d = p.plan(30, 700);
        assertEquals(27, d.desiredReplicas(), "at most 10% per cycle");
    }

    @Test
    void fallsBackToReactiveSizingWhileTheForecasterWarmsUp() {
        CapacityPlanner p = planner(forecaster(false, t -> 0.0));
        ScalingDecision d = p.plan(4, 7_000);
        assertEquals(ScalingDecision.Mode.REACTIVE, d.mode());
        assertEquals(10, d.desiredReplicas());
    }

    @Test
    void respectsReplicaBounds() {
        CapacityPlanner p = planner(forecaster(true, t -> 1_000_000.0));
        assertEquals(40, p.plan(10, 0).desiredReplicas());
        CapacityPlanner idle = planner(forecaster(true, t -> 0.0));
        assertEquals(2, idle.plan(2, 0).desiredReplicas());
    }
}
