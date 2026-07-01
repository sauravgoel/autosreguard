package io.autosreguard.inference;

import io.autosreguard.core.Segment;
import io.autosreguard.core.TenantId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadShedderTest {

    private static InferenceRequest req(String id, Segment s) {
        return new InferenceRequest(id, TenantId.of("t"), s, 100, 100, 1000, true, "", 0);
    }

    @Test
    void shedsConsumerFirstThenApiAndEnterpriseLast() {
        LoadShedder s = new LoadShedder();
        s.setLevel(0.5);
        assertEquals(1.0, s.sheddingFractionFor(Segment.CONSUMER), 1e-9);
        assertEquals(0.5, s.sheddingFractionFor(Segment.API), 1e-9);
        assertEquals(0.0, s.sheddingFractionFor(Segment.ENTERPRISE), 1e-9);
        s.setLevel(1.0);
        assertEquals(1.0, s.sheddingFractionFor(Segment.ENTERPRISE), 1e-9);
    }

    @Test
    void admissionRateTracksConfiguredFraction() {
        LoadShedder s = new LoadShedder();
        s.setLevel(0.5); // API at 50%
        int admitted = 0;
        for (int i = 0; i < 20_000; i++) {
            if (s.admit(req("r-" + i, Segment.API))) {
                admitted++;
            }
        }
        assertEquals(0.5, admitted / 20_000.0, 0.02);
    }

    @Test
    void decisionsAreDeterministicPerRequestSoRetriesDoNotStorm() {
        LoadShedder s = new LoadShedder();
        s.setLevel(0.5);
        for (int i = 0; i < 100; i++) {
            InferenceRequest r = req("r-" + i, Segment.API);
            assertEquals(s.admit(r), s.admit(r));
        }
    }

    @Test
    void zeroLevelAdmitsEverything() {
        LoadShedder s = new LoadShedder();
        for (Segment seg : Segment.values()) {
            assertTrue(s.admit(req("x", seg)));
        }
        assertThrows(IllegalArgumentException.class, () -> s.setLevel(1.5));
    }
}
