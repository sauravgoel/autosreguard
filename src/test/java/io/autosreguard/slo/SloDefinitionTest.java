package io.autosreguard.slo;

import io.autosreguard.core.Segment;
import io.autosreguard.core.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SloDefinitionTest {

    private static final Instant T = Instant.parse("2026-04-17T10:00:00Z");
    private static final TenantId ACME = TenantId.of("acme");

    @Test
    void errorBudgetIsOneMinusTarget() {
        assertEquals(0.0005, SloDefinition.availability("a", Segment.ENTERPRISE, 0.9995).errorBudget(), 1e-12);
    }

    @Test
    void appliesOnlyToItsSegment() {
        SloDefinition slo = SloDefinition.availability("a", Segment.ENTERPRISE, 0.999);
        assertTrue(slo.appliesTo(InferenceOutcome.failure(T, ACME, Segment.ENTERPRISE, "m", "r")));
        assertFalse(slo.appliesTo(InferenceOutcome.failure(T, ACME, Segment.CONSUMER, "m", "r")));
    }

    @Test
    void ttftSloCountsSlowAndFailedRequestsAsBad() {
        SloDefinition slo = SloDefinition.ttft("t", Segment.ENTERPRISE, 0.99, 800);
        assertTrue(slo.isGood(InferenceOutcome.success(T, ACME, Segment.ENTERPRISE, "m", "r", 799, 20, 10)));
        assertFalse(slo.isGood(InferenceOutcome.success(T, ACME, Segment.ENTERPRISE, "m", "r", 801, 20, 10)));
        assertFalse(slo.isGood(InferenceOutcome.failure(T, ACME, Segment.ENTERPRISE, "m", "r")));
    }
}
