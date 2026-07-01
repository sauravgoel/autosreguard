package io.autosreguard.runtime;

import io.autosreguard.config.AutoSREGuardConfig;
import io.autosreguard.core.Clocks;
import io.autosreguard.detection.EwmaAnomalyDetector;
import io.autosreguard.detection.Signals;
import io.autosreguard.diagnosis.ChangeEvent;
import io.autosreguard.diagnosis.ChangeLog;
import io.autosreguard.diagnosis.ServiceGraph;
import io.autosreguard.remediation.AuditLog;
import io.autosreguard.remediation.AuditRecord;
import io.autosreguard.remediation.FleetState;
import io.autosreguard.remediation.RemediationAction;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end: detector -> diagnosis -> remediation through the real factory wiring. */
class AutoSREGuardTest {

    private static final Instant T0 = Instant.parse("2026-04-17T14:00:00Z");
    private static final String POOL = "pool-llama-70b";

    private static final FleetState FLEET = new FleetState() {
        @Override
        public int replicas(String pool) {
            return 4;
        }

        @Override
        public double sheddingFraction(String service) {
            return 0;
        }

        @Override
        public int maxReplicas(String pool) {
            return 16;
        }
    };

    private final Clocks.ManualClock clock = Clocks.manual(T0);
    private final ChangeLog changes = new ChangeLog(100);
    private final List<RemediationAction> executed = new ArrayList<>();
    private final AuditLog audit = AuditLog.inMemory(100);

    private AutoSREGuard create(boolean dryRun) {
        Properties p = new Properties();
        p.setProperty("remediation.dry-run", Boolean.toString(dryRun));
        ServiceGraph graph = new ServiceGraph().dependsOn("inference-router", POOL);
        AutoSREGuard ig = AutoSREGuardFactory.create(AutoSREGuardConfig.of(p, Map.of()), clock, graph, changes, FLEET,
                executed::add, n -> { }, audit);
        ig.registerDetector(EwmaAnomalyDetector.builder(POOL, Signals.ERROR_RATE).minSigma(0.001).build());
        return ig;
    }

    /** 40 healthy samples, then a deploy, then sustained 5% errors. */
    private void incident(AutoSREGuard ig) {
        for (int i = 0; i < 40; i++) {
            ig.observeSignal(POOL, Signals.ERROR_RATE, clock.instant(), 0.002);
            clock.advance(Duration.ofSeconds(30));
        }
        changes.record(new ChangeEvent("deploy-1", ChangeEvent.Kind.DEPLOYMENT, POOL, "v42", "v43",
                clock.instant(), "ci"));
        clock.advance(Duration.ofSeconds(30));
        for (int i = 0; i < 3; i++) {
            ig.observeSignal(POOL, Signals.ERROR_RATE, clock.instant(), 0.05);
            clock.advance(Duration.ofSeconds(30));
        }
    }

    @Test
    void badDeployIsRolledBackAutomaticallyAndRecordedAsAnAutomatedChange() {
        AutoSREGuard ig = create(false);
        incident(ig);
        ControlCycleReport r = ig.runCycle();

        assertEquals(1, r.outcomes().size(), r.toString());
        assertEquals(AuditRecord.Status.EXECUTED, r.outcomes().get(0).status());
        assertEquals(List.of(new RemediationAction.Rollback(POOL, "v43", "v42")), executed);
        // The rollback itself is now in the change log, authored by automation, so the next
        // diagnosis does not mistake our fix for a new suspicious change.
        ChangeEvent latest = changes.latestFor(POOL).orElseThrow();
        assertTrue(latest.isAutomated());
        assertTrue(ig.metrics().scrape().contains("autosreguard_remediations_total"));
    }

    @Test
    void afterActingItWaitsForFreshEvidenceInsteadOfReusingStaleAnomalies() {
        AutoSREGuard ig = create(false);
        incident(ig);
        ig.runCycle();
        clock.advance(Duration.ofSeconds(15));
        ControlCycleReport second = ig.runCycle();
        assertTrue(second.anomalies().isEmpty(), "anomalies are cleared after an executed action");
        assertTrue(second.outcomes().isEmpty(), second.toString());
    }

    @Test
    void ifErrorsPersistAfterTheFixItPagesInsteadOfPilingOnMoreAutomation() {
        AutoSREGuard ig = create(false);
        incident(ig);
        ig.runCycle();
        // The rollback did not help: errors continue. The deploy is now superseded by our own
        // rollback, so diagnosis falls back to a component fault, and the cooldown guard stops
        // a second automated action on the same target within minutes.
        ig.observeSignal(POOL, Signals.ERROR_RATE, clock.instant(), 0.05);
        clock.advance(Duration.ofSeconds(15));
        ControlCycleReport second = ig.runCycle();
        assertEquals(1, second.outcomes().size(), second.toString());
        assertEquals(AuditRecord.Status.SUPPRESSED, second.outcomes().get(0).status());
        assertEquals(1, executed.size());
    }

    @Test
    void dryRunAuditsDecisionsButTouchesNothing() {
        AutoSREGuard ig = create(true);
        incident(ig);
        ControlCycleReport r = ig.runCycle();
        assertEquals(AuditRecord.Status.EXECUTED, r.outcomes().get(0).status());
        assertTrue(executed.isEmpty(), "dry-run must never reach the real executor");
        assertEquals("deploy-1", changes.latestFor(POOL).orElseThrow().id());
    }

    @Test
    void quietSystemProducesNoDecisions() {
        AutoSREGuard ig = create(false);
        for (int i = 0; i < 60; i++) {
            ig.observeSignal(POOL, Signals.ERROR_RATE, clock.instant(), 0.002);
            clock.advance(Duration.ofSeconds(30));
        }
        assertTrue(ig.runCycle().outcomes().isEmpty());
        assertTrue(audit.recent().isEmpty());
    }
}
