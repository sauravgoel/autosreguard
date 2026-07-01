package io.autosreguard.detection;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EwmaAnomalyDetectorTest {

    private static final Instant T0 = Instant.parse("2026-04-17T14:00:00Z");

    private static EwmaAnomalyDetector detector() {
        return EwmaAnomalyDetector.builder("pool-llama-70b", Signals.ERROR_RATE)
                .warmupSamples(30).consecutive(3).zThreshold(4).minSigma(0.001).build();
    }

    private static void warm(EwmaAnomalyDetector d) {
        for (int i = 0; i < 60; i++) {
            d.observe(T0.plusSeconds(i), 0.002 + (i % 2 == 0 ? 0.0002 : -0.0002));
        }
    }

    @Test
    void staysQuietDuringWarmupEvenForWildValues() {
        EwmaAnomalyDetector d = detector();
        for (int i = 0; i < 29; i++) {
            assertFalse(d.observe(T0.plusSeconds(i), i % 2 == 0 ? 0.0 : 0.5).isPresent());
        }
    }

    @Test
    void singleSpikeIsIgnoredButASustainedShiftFires() {
        EwmaAnomalyDetector d = detector();
        warm(d);
        assertFalse(d.observe(T0.plusSeconds(100), 0.05).isPresent());
        assertFalse(d.observe(T0.plusSeconds(101), 0.002).isPresent(), "run resets on a normal sample");

        assertFalse(d.observe(T0.plusSeconds(102), 0.05).isPresent());
        assertFalse(d.observe(T0.plusSeconds(103), 0.05).isPresent());
        Optional<Anomaly> a = d.observe(T0.plusSeconds(104), 0.05);
        assertTrue(a.isPresent());
        assertEquals("pool-llama-70b", a.get().component());
        assertTrue(a.get().zScore() > 4);
        assertTrue(a.get().confidence() > 0 && a.get().confidence() <= 1);
    }

    @Test
    void anomalousSamplesDoNotDragTheBaselineUp() {
        EwmaAnomalyDetector d = detector();
        warm(d);
        double before = d.baseline();
        for (int i = 0; i < 20; i++) {
            d.observe(T0.plusSeconds(200 + i), 0.08);
        }
        assertEquals(before, d.baseline(), 1e-12);
    }

    @Test
    void upDetectorIgnoresDrops() {
        EwmaAnomalyDetector d = detector();
        warm(d);
        for (int i = 0; i < 5; i++) {
            assertFalse(d.observe(T0.plusSeconds(300 + i), -1.0).isPresent());
        }
    }

    @Test
    void signalsClassifyHardwareAndSaturation() {
        assertTrue(Signals.isHardware(Signals.GPU_ECC_ERRORS));
        assertFalse(Signals.isHardware(Signals.ERROR_RATE));
        assertTrue(Signals.isSaturation(Signals.QUEUE_DEPTH));
    }
}
