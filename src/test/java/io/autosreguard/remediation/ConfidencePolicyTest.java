package io.autosreguard.remediation;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfidencePolicyTest {

    private final ConfidencePolicy policy = ConfidencePolicy.defaults();

    @Test
    void mapsConfidenceToTheThreeAutonomyTiers() {
        assertEquals(AutonomyTier.AUTO_REMEDIATE, policy.tierFor(0.90, BlastRadius.LOW));
        assertEquals(AutonomyTier.SUGGEST_AND_NOTIFY, policy.tierFor(0.70, BlastRadius.LOW));
        assertEquals(AutonomyTier.PAGE_AND_ASSIST, policy.tierFor(0.40, BlastRadius.LOW));
    }

    @Test
    void biggerBlastRadiusDemandsMoreConfidenceToActAlone() {
        assertEquals(AutonomyTier.AUTO_REMEDIATE, policy.tierFor(0.87, BlastRadius.LOW));
        assertEquals(AutonomyTier.SUGGEST_AND_NOTIFY, policy.tierFor(0.87, BlastRadius.HIGH));
        assertEquals(AutonomyTier.AUTO_REMEDIATE, policy.tierFor(0.93, BlastRadius.HIGH));
    }

    @Test
    void rejectsInconsistentThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new ConfidencePolicy(0.5, 0.8, Duration.ofMinutes(3)));
    }
}
