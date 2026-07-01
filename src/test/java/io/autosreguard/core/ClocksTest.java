package io.autosreguard.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClocksTest {

    @Test
    void manualClockOnlyMovesWhenAdvanced() {
        Instant t0 = Instant.parse("2026-04-17T00:00:00Z");
        Clocks.ManualClock clock = Clocks.manual(t0);
        assertEquals(t0, clock.instant());
        clock.advance(Duration.ofMinutes(5));
        assertEquals(t0.plusSeconds(300), clock.instant());
    }
}
