package io.autosreguard.inference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveLatencyModelTest {

    private static final ModelVariant V = new ModelVariant("v", 0.9, 300, 100, 20, 8192, 1.0);

    @Test
    void unloadedEstimateIsTheStaticProfile() {
        LiveLatencyModel m = new LiveLatencyModel(0.2);
        assertEquals(300 + 100 * 2.0, m.estimateTtftMs(V, 2000), 1e-9);
        assertEquals(20, m.estimateTpotMs(V), 1e-9);
    }

    @Test
    void queueingInflatesTtftLikeMm1() {
        LiveLatencyModel m = new LiveLatencyModel(0.2);
        m.setPressure("v", 0.5);
        assertEquals(2 * 400, m.estimateTtftMs(V, 1000), 1e-9);
        m.setPressure("v", 5.0);
        assertTrue(Double.isFinite(m.estimateTtftMs(V, 1000)), "capped, never infinite");
    }

    @Test
    void observationsPullEstimatesTowardsReality() {
        LiveLatencyModel m = new LiveLatencyModel(0.5);
        for (int i = 0; i < 20; i++) {
            m.observe(V, 1000, 800, 40); // everything is 2x slower than the profile says
        }
        assertEquals(800, m.estimateTtftMs(V, 1000), 1.0);
        assertEquals(40, m.estimateTpotMs(V), 0.1);
    }
}
