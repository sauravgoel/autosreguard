package io.autosreguard.remediation;

import io.autosreguard.core.Clocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OscillationGuardTest {

    private Clocks.ManualClock clock;
    private OscillationGuard guard;

    @BeforeEach
    void setUp() {
        clock = Clocks.manual(Instant.parse("2026-04-17T14:00:00Z"));
        guard = new OscillationGuard(clock, Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofMinutes(10), 3);
    }

    @Test
    void allowsTheFirstAction() {
        assertEquals("", guard.check(new RemediationAction.Rollback("pool", "v43", "v42")));
    }

    @Test
    void cooldownBlocksRepeatedActionsOnTheSameTarget() {
        guard.recordExecuted(new RemediationAction.Rollback("pool", "v43", "v42"));
        clock.advance(Duration.ofMinutes(2));
        assertTrue(guard.check(new RemediationAction.ShedLoad("pool", 0, 0.25)).startsWith("cooldown"));
        clock.advance(Duration.ofMinutes(4));
        assertEquals("", guard.check(new RemediationAction.ShedLoad("pool", 0, 0.25)));
    }

    @Test
    void flapProtectionBlocksUndoingARecentAction() {
        RemediationAction up = new RemediationAction.ScaleReplicas("pool", 4, 8);
        guard.recordExecuted(up);
        clock.advance(Duration.ofMinutes(10)); // past cooldown, inside flap window
        assertTrue(guard.check(up.inverse()).startsWith("flap"));
    }

    @Test
    void globalRateLimitCapsAutomationDuringAStorm() {
        for (int i = 0; i < 3; i++) {
            guard.recordExecuted(new RemediationAction.DrainNode("node-" + i, true));
        }
        assertTrue(guard.check(new RemediationAction.DrainNode("node-9", true)).startsWith("rate limit"));
        clock.advance(Duration.ofMinutes(11));
        assertEquals("", guard.check(new RemediationAction.DrainNode("node-9", true)));
    }
}
