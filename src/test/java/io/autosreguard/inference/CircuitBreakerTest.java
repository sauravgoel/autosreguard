package io.autosreguard.inference;

import io.autosreguard.core.Clocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    private Clocks.ManualClock clock;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = Clocks.manual(Instant.parse("2026-04-17T18:00:00Z"));
        breaker = new CircuitBreaker("fp16", clock, 0.5, 10, 20, Duration.ofSeconds(30), 2);
    }

    private void calls(int failures, int successes) {
        for (int i = 0; i < failures; i++) {
            breaker.onFailure();
        }
        for (int i = 0; i < successes; i++) {
            breaker.onSuccess();
        }
    }

    @Test
    void doesNotTripBeforeMinimumCalls() {
        calls(9, 0);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void tripsWhenFailureRatioExceedsThreshold() {
        calls(0, 4);
        calls(7, 0);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire());
    }

    @Test
    void halfOpensAfterCooldownAndClosesAfterSuccessfulProbes() {
        calls(12, 0);
        clock.advance(Duration.ofSeconds(31));
        assertTrue(breaker.tryAcquire());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertTrue(breaker.tryAcquire());
        assertFalse(breaker.tryAcquire(), "probe budget exhausted");
        breaker.onSuccess();
        breaker.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void aFailedProbeReopensImmediately() {
        calls(12, 0);
        clock.advance(Duration.ofSeconds(31));
        assertTrue(breaker.tryAcquire());
        breaker.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    }
}
