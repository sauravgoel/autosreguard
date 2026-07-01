package io.autosreguard.demo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scenarios are deterministic, so their narrative doubles as an end-to-end regression
 * test: if a tuning change alters what the demo claims, this fails.
 */
class ScenarioSmokeTest {

    private static String run(Scenario s) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        s.run(new Console(new PrintStream(buf, true, StandardCharsets.UTF_8)));
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void hiddenTenantIsPagedWhileMaskedByTheAggregate() throws Exception {
        String out = run(new HiddenTenantScenario());
        assertTrue(out.contains("[PAGE] slo=enterprise-availability tenant=acme-corp"), out);
        assertTrue(out.contains("MASKED-BY-AGGREGATE"), out);
    }

    @Test
    void badDeployIsRolledBackAutomaticallyAndTheSecondFixWaitsForTheCooldown() throws Exception {
        String out = run(new BadDeployScenario());
        assertTrue(out.contains("AUTO_REMEDIATE -> EXECUTED: roll back pool-llama-70b v43 -> v42"), out);
        assertTrue(out.contains("SUPPRESSED"), out);
        assertTrue(out.contains("EXECUTED: revert pool-llama-70b config max_batch_tokens"), out);
    }

    @Test
    void degradingGpuIsDrainedAndHardFaultIsDrainedAutomatically() throws Exception {
        String out = run(new GpuDegradationScenario());
        assertTrue(out.contains("SUGGEST_AND_NOTIFY -> EXECUTED: drain gpu-node-05"), out);
        assertTrue(out.contains("AUTO_REMEDIATE -> EXECUTED: drain gpu-node-02"), out);
        assertTrue(out.contains("unplaced=[]"), out);
    }

    @Test
    void predictiveScalingAvoidsUnderProvisioning() throws Exception {
        String out = run(new PredictiveScalingScenario());
        assertTrue(out.contains("predictive: under-provisioned intervals=0"), out);
    }

    @Test
    void gracefulDegradationNeverRejectsInTheDemo() throws Exception {
        String out = run(new GracefulDegradationScenario());
        assertTrue(!out.contains("REJECTED"), out);
        assertTrue(out.contains("llama-70b-int8 (degraded)"), out);
    }

    @Test
    void canaryIsRolledBackOnQualityWhileOpsOnlyWouldPromote() throws Exception {
        String out = run(new CanaryScenario());
        assertTrue(out.contains("quality-aware verdict: ROLLBACK   ops-only verdict: PROMOTE"), out);
    }

    @Test
    void onlyTheConservativeShadowCandidatePassesTheGate() throws Exception {
        String out = run(new ShadowScenario());
        assertTrue(out.contains("FAIL -> stays in shadow"), out);
        assertTrue(out.contains("PASS -> ready for human review"), out);
    }
}
