package io.autosreguard.canary;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanaryAnalyzerTest {

    private final CanaryAnalyzer analyzer = CanaryAnalyzer.defaults();

    /** Deterministic arm: {@code errorEvery}-th request fails, every 5th success is scored. */
    private static CanarySample arm(int n, int errorEvery, double latencyMs, double quality, long seed) {
        CanarySample s = new CanarySample();
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int i = 0; i < n; i++) {
            boolean error = errorEvery > 0 && i % errorEvery == 0;
            s.recordRequest(error, latencyMs * (0.8 + 0.4 * rnd.nextDouble()));
            if (!error && i % 5 == 0) {
                s.recordQuality(Math.max(0, Math.min(1, quality + (rnd.nextDouble() - 0.5) * 0.2)));
            }
        }
        return s;
    }

    @Test
    void continuesUntilBothArmsHaveEnoughData() {
        CanaryVerdict v = analyzer.analyze(arm(300, 0, 900, 0.8, 1), arm(300, 0, 900, 0.8, 2));
        assertEquals(CanaryVerdict.Decision.CONTINUE, v.decision());
    }

    @Test
    void promotesAnEquivalentCanary() {
        CanaryVerdict v = analyzer.analyze(arm(2000, 1000, 900, 0.80, 1), arm(2000, 1000, 900, 0.80, 2));
        assertEquals(CanaryVerdict.Decision.PROMOTE, v.decision(), v.reasons().toString());
    }

    @Test
    void rollsBackOnQualityRegressionEvenWhenFasterAndEquallyReliable() {
        CanaryVerdict v = analyzer.analyze(arm(2000, 1000, 900, 0.82, 1), arm(2000, 1000, 800, 0.77, 2));
        assertEquals(CanaryVerdict.Decision.ROLLBACK, v.decision());
        assertTrue(v.reasons().stream().anyMatch(r -> r.startsWith("quality") && r.endsWith("FAIL")));
    }

    @Test
    void rollsBackOnErrorRegression() {
        CanaryVerdict v = analyzer.analyze(arm(2000, 1000, 900, 0.8, 1), arm(2000, 50, 900, 0.8, 2));
        assertEquals(CanaryVerdict.Decision.ROLLBACK, v.decision());
    }

    @Test
    void rollsBackOnLatencyRegression() {
        CanaryVerdict v = analyzer.analyze(arm(2000, 1000, 900, 0.8, 1), arm(2000, 1000, 1400, 0.8, 2));
        assertEquals(CanaryVerdict.Decision.ROLLBACK, v.decision());
    }

    @Test
    void failsFastOnClearBadNewsBeforeQualitySamplesArrive() {
        CanaryVerdict v = analyzer.analyze(arm(1000, 1000, 900, 0.8, 1), arm(1000, 20, 900, 0.8, 2));
        assertEquals(CanaryVerdict.Decision.ROLLBACK, v.decision());
    }
}
