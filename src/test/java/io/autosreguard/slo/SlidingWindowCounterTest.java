package io.autosreguard.slo;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlidingWindowCounterTest {

    private static final Instant T0 = Instant.parse("2026-04-17T10:00:00Z");

    @Test
    void sumsOnlyBucketsInsideTheRequestedWindow() {
        SlidingWindowCounter c = new SlidingWindowCounter(Duration.ofHours(1), Duration.ofMinutes(1));
        for (int m = 0; m < 10; m++) {
            c.record(T0.plusSeconds(60L * m), 90, 100); // 10% errors every minute
        }
        Instant now = T0.plusSeconds(60L * 9);
        SlidingWindowCounter.Counts last5 = c.sum(Duration.ofMinutes(5), now);
        assertEquals(500, last5.total());
        assertEquals(450, last5.good());
        assertEquals(0.10, last5.errorRate(), 1e-9);
        assertEquals(1000, c.sum(Duration.ofHours(1), now).total());
    }

    @Test
    void oldBucketsAreRecycledAfterTheHorizon() {
        SlidingWindowCounter c = new SlidingWindowCounter(Duration.ofMinutes(10), Duration.ofMinutes(1));
        c.record(T0, false);
        Instant later = T0.plus(Duration.ofMinutes(30));
        c.record(later, true);
        SlidingWindowCounter.Counts counts = c.sum(Duration.ofMinutes(10), later);
        assertEquals(1, counts.total());
        assertEquals(0.0, counts.errorRate(), 0.0);
    }

    @Test
    void emptyWindowHasZeroErrorRateNotNaN() {
        SlidingWindowCounter c = new SlidingWindowCounter(Duration.ofMinutes(10), Duration.ofMinutes(1));
        assertEquals(0.0, c.sum(Duration.ofMinutes(5), T0).errorRate(), 0.0);
    }

    @Test
    void rejectsZeroWidthBuckets() {
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCounter(Duration.ofMinutes(10), Duration.ZERO));
    }
}
