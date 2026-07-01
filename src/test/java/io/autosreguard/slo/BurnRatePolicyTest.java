package io.autosreguard.slo;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BurnRatePolicyTest {

    @Test
    void defaultsFollowMultiWindowMultiBurnRatePractice() {
        BurnRatePolicy p = BurnRatePolicy.defaults();
        assertEquals(3, p.rules().size());
        assertEquals(Duration.ofHours(24), p.longestWindow());
        assertTrue(p.minEvents() > 0, "low-traffic tenants must not page on a single error");
    }
}
