package io.autosreguard.inference;

import io.autosreguard.core.Preconditions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Classic three-state circuit breaker over a rolling count window.
 *
 * <pre>
 *   CLOSED --(failure ratio > threshold over >= minCalls)--> OPEN
 *   OPEN   --(openDuration elapsed)-----------------------> HALF_OPEN
 *   HALF_OPEN --(probe succeeds x probes)------------------> CLOSED
 *   HALF_OPEN --(any probe fails)--------------------------> OPEN
 * </pre>
 *
 * <p>For LLM serving, an open breaker on a variant doesn't mean an error to the user: the
 * router skips it and degrades to the next variant. Breakers are what turn a failing
 * GPU pool into graceful degradation instead of a cascading timeout storm.
 *
 * <p>Results reported while OPEN are ignored: they belong to calls admitted before the trip,
 * and letting them re-trip would extend the cooldown for as long as stragglers keep arriving.
 * In HALF_OPEN only as many successes as probes handed out count towards closing.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final Clock clock;
    private final double failureRatioThreshold;
    private final int minCalls;
    private final int windowSize;
    private final Duration openDuration;
    private final int halfOpenProbes;

    private State state = State.CLOSED;
    private Instant openedAt;
    private final boolean[] window;
    private int windowPos;
    private int windowCount;
    private int windowFailures;
    private int halfOpenInFlight;
    private int halfOpenSuccesses;

    public CircuitBreaker(String name, Clock clock, double failureRatioThreshold, int minCalls, int windowSize,
                          Duration openDuration, int halfOpenProbes) {
        this.name = Objects.requireNonNull(name, "name");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.failureRatioThreshold = Preconditions.checkFraction(failureRatioThreshold, "failureRatioThreshold");
        this.minCalls = Preconditions.checkPositive(minCalls, "minCalls");
        this.windowSize = Preconditions.checkPositive(windowSize, "windowSize");
        this.openDuration = Objects.requireNonNull(openDuration, "openDuration");
        this.halfOpenProbes = Preconditions.checkPositive(halfOpenProbes, "halfOpenProbes");
        if (minCalls > windowSize) {
            throw new IllegalArgumentException("minCalls must be <= windowSize");
        }
        this.window = new boolean[windowSize];
    }

    public static CircuitBreaker defaults(String name, Clock clock) {
        return new CircuitBreaker(name, clock, 0.5, 20, 100, Duration.ofSeconds(30), 3);
    }

    /**
     * Whether a call may proceed. In HALF_OPEN only a limited number of probes are allowed;
     * callers MUST report the result via {@link #onSuccess()} or {@link #onFailure()}.
     */
    public synchronized boolean tryAcquire() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration))) {
            state = State.HALF_OPEN;
            halfOpenInFlight = 0;
            halfOpenSuccesses = 0;
        }
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> {
                if (halfOpenInFlight < halfOpenProbes) {
                    halfOpenInFlight++;
                    yield true;
                }
                yield false;
            }
        };
    }

    /** Non-mutating check used by the router when ranking candidates. */
    public synchronized boolean isCallPermitted() {
        if (state == State.OPEN) {
            return !clock.instant().isBefore(openedAt.plus(openDuration));
        }
        return state == State.CLOSED || halfOpenInFlight < halfOpenProbes;
    }

    public synchronized void onSuccess() {
        if (state == State.OPEN) {
            return; // late result of a call admitted before the trip; it says nothing new
        }
        if (state == State.HALF_OPEN) {
            if (halfOpenSuccesses >= halfOpenInFlight) {
                return; // not one of our probes, so it must not close the breaker
            }
            halfOpenSuccesses++;
            if (halfOpenSuccesses >= halfOpenProbes) {
                reset();
            }
            return;
        }
        push(false);
    }

    public synchronized void onFailure() {
        if (state == State.OPEN) {
            return; // late result; re-tripping here would keep pushing the cooldown out
        }
        if (state == State.HALF_OPEN) {
            trip();
            return;
        }
        push(true);
        if (windowCount >= minCalls && (double) windowFailures / windowCount > failureRatioThreshold) {
            trip();
        }
    }

    private void push(boolean failed) {
        if (windowCount == windowSize) {
            if (window[windowPos]) {
                windowFailures--;
            }
        } else {
            windowCount++;
        }
        window[windowPos] = failed;
        if (failed) {
            windowFailures++;
        }
        windowPos = (windowPos + 1) % windowSize;
    }

    private void trip() {
        state = State.OPEN;
        openedAt = clock.instant();
    }

    private void reset() {
        state = State.CLOSED;
        windowPos = 0;
        windowCount = 0;
        windowFailures = 0;
        java.util.Arrays.fill(window, false);
    }

    public synchronized State state() {
        // Reflect a lapsed OPEN window without requiring a call.
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration))) {
            return State.HALF_OPEN;
        }
        return state;
    }

    public String name() {
        return name;
    }
}
