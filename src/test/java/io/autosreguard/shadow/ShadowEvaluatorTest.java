package io.autosreguard.shadow;

import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowEvaluatorTest {

    @Test
    void alwaysReturnsTheIncumbentsDecision() {
        ShadowEvaluator<Integer, Integer> e = new ShadowEvaluator<>("x", i -> i, i -> -i, Objects::equals, 5);
        assertEquals(7, e.evaluate(7).intValue());
    }

    @Test
    void measuresAgreementAndKeepsRecentDivergences() {
        ShadowEvaluator<Integer, Boolean> e = new ShadowEvaluator<>("parity",
                i -> i % 2 == 0, i -> i % 2 == 0 || i % 5 == 0, Objects::equals, 3);
        for (int i = 0; i < 100; i++) {
            e.evaluate(i);
        }
        ShadowEvaluator.Report r = e.report();
        assertEquals(100, r.total());
        assertEquals(0.90, r.agreementRate(), 1e-9); // odd multiples of 5 disagree: 10 of 100
        assertEquals(3, r.divergences().size());
    }

    @Test
    void candidateExceptionsAreCountedAndNeverReachTheCaller() {
        ShadowEvaluator<Integer, Integer> e = new ShadowEvaluator<>("x", i -> i, i -> {
            throw new IllegalStateException("model not loaded");
        }, Objects::equals, 5);
        assertEquals(1, e.evaluate(1).intValue());
        assertEquals(1, e.report().candidateErrors());
        assertTrue(e.report().divergences().get(0).contains("IllegalStateException"));
    }

    @Test
    void readinessGateNeedsVolumeAgreementAndNoErrors() {
        ShadowEvaluator<Integer, Integer> e = new ShadowEvaluator<>("x", i -> i, i -> i, Objects::equals, 5);
        for (int i = 0; i < 99; i++) {
            e.evaluate(i);
        }
        assertFalse(e.readyForReview(100, 0.99, 0.0), "not enough samples");
        e.evaluate(99);
        assertTrue(e.readyForReview(100, 0.99, 0.0));
    }
}
