package io.autosreguard.remediation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RemediationActionTest {

    @Test
    void everyActionIsReversibleAndTheInverseOfTheInverseIsItself() {
        List<RemediationAction> actions = List.of(
                new RemediationAction.Rollback("pool", "v43", "v42"),
                new RemediationAction.RevertConfig("pool", "max_batch_tokens", "32768", "8192"),
                new RemediationAction.ShedLoad("router", 0.0, 0.25),
                new RemediationAction.ScaleReplicas("pool", 4, 6),
                new RemediationAction.DrainNode("gpu-node-05", true));
        for (RemediationAction a : actions) {
            assertEquals(a, a.inverse().inverse(), a.type());
            assertFalse(a.describe().isBlank());
        }
    }
}
