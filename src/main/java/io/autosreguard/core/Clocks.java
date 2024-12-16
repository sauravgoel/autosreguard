package io.autosreguard.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Time utilities.
 *
 * <p>Every time-dependent component in AutoSREGuard takes a {@link Clock}. Reliability logic
 * (burn rates, cooldowns, circuit-breaker timeouts) is notoriously hard to test against the
 * wall clock; injecting time makes every one of those paths deterministic in tests and in
 * the simulator.
 */
public final class Clocks {

    private Clocks() {
    }

    /** Wall clock in UTC. Use in production wiring only. */
    public static Clock system() {
        return Clock.systemUTC();
    }

    /** A clock that only moves when told to. Used by tests and the simulation harness. */
    public static ManualClock manual(Instant start) {
        return new ManualClock(start);
    }

    /**
     * Mutable, thread-safe clock. {@link #advance(Duration)} lets a test fast-forward through
     * an hour of burn-rate windows in microseconds.
     */
    public static final class ManualClock extends Clock {

        private final AtomicReference<Instant> now;

        private ManualClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        /** Moves time forward. Negative durations are rejected: time never goes backwards. */
        public void advance(Duration d) {
            if (d.isNegative()) {
                throw new IllegalArgumentException("clock cannot move backwards: " + d);
            }
            now.updateAndGet(t -> t.plus(d));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            // Zone is irrelevant for our epoch-based arithmetic; return self.
            return this;
        }
    }
}
