package io.autosreguard.remediation;

import io.autosreguard.core.Clocks;
import io.autosreguard.diagnosis.ChangeEvent;
import io.autosreguard.diagnosis.Hypothesis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemediationEngineTest {

    private static final Instant T0 = Instant.parse("2026-04-17T14:21:00Z");

    private Clocks.ManualClock clock;
    private final List<RemediationAction> executed = new ArrayList<>();
    private final List<Notifier.Notification> notifications = new ArrayList<>();
    private AuditLog audit;
    private RemediationEngine engine;

    /** Minimal fleet: 4 replicas, no shedding, max 16. */
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

    @BeforeEach
    void setUp() {
        clock = Clocks.manual(T0);
        audit = AuditLog.inMemory(100);
        engine = new RemediationEngine(RemediationPlanner.defaults(FLEET), ConfidencePolicy.defaults(),
                OscillationGuard.defaults(clock), executed::add, notifications::add, audit, clock);
    }

    private static Hypothesis badDeploy(double confidence) {
        ChangeEvent change = new ChangeEvent("deploy-8812", ChangeEvent.Kind.DEPLOYMENT, "pool-llama-70b",
                "v42", "v43", T0.minusSeconds(60), "ci");
        return new Hypothesis(Hypothesis.Kind.BAD_CHANGE, "pool-llama-70b", Optional.of(change), confidence,
                List.of("error_rate z=12"));
    }

    @Test
    void highConfidenceExecutesImmediatelyAndIsAudited() {
        RemediationOutcome o = engine.handle(badDeploy(0.95));
        assertEquals(AutonomyTier.AUTO_REMEDIATE, o.tier());
        assertEquals(AuditRecord.Status.EXECUTED, o.status());
        assertEquals(List.of(new RemediationAction.Rollback("pool-llama-70b", "v43", "v42")), executed);

        AuditRecord r = audit.latest(o.actionId()).orElseThrow();
        assertEquals(RemediationEngine.SYSTEM_ACTOR, r.actor());
        assertTrue(r.inverse().contains("v42 -> v43"), "logged, explainable AND reversible");
        assertEquals(List.of("error_rate z=12"), r.evidence());
    }

    @Test
    void mediumConfidenceProposesAndExecutesOnlyAfterTheVetoWindow() {
        RemediationOutcome o = engine.handle(badDeploy(0.70));
        assertEquals(AuditRecord.Status.PROPOSED, o.status());
        assertTrue(executed.isEmpty());
        assertEquals(Notifier.Urgency.APPROVAL_REQUESTED, notifications.get(0).urgency());

        clock.advance(Duration.ofMinutes(2));
        assertTrue(engine.tick().isEmpty());
        clock.advance(Duration.ofMinutes(1));
        List<RemediationOutcome> due = engine.tick();
        assertEquals(1, due.size());
        assertEquals(AuditRecord.Status.EXECUTED, due.get(0).status());
        assertEquals(1, executed.size());
    }

    @Test
    void aVetoedProposalNeverRuns() {
        String id = engine.handle(badDeploy(0.70)).actionId();
        engine.veto(id, "alice", "the deploy is fine; it's the upstream");
        clock.advance(Duration.ofMinutes(10));
        assertTrue(engine.tick().isEmpty());
        assertTrue(executed.isEmpty());
        assertEquals(AuditRecord.Status.VETOED, audit.latest(id).orElseThrow().status());
        assertEquals("alice", audit.latest(id).orElseThrow().actor());
    }

    @Test
    void approvalExecutesEarlyAndIsAttributedToTheHuman() {
        String id = engine.handle(badDeploy(0.70)).actionId();
        RemediationOutcome o = engine.approve(id, "bob");
        assertEquals(AuditRecord.Status.EXECUTED, o.status());
        assertEquals("bob", audit.latest(id).orElseThrow().actor());
        assertThrows(IllegalArgumentException.class, () -> engine.approve(id, "bob"));
    }

    @Test
    void lowConfidencePagesWithACandidateButDoesNothing() {
        RemediationOutcome o = engine.handle(badDeploy(0.40));
        assertEquals(AutonomyTier.PAGE_AND_ASSIST, o.tier());
        assertEquals(AuditRecord.Status.ESCALATED, o.status());
        assertTrue(o.action().isPresent(), "the page carries the candidate action");
        assertTrue(executed.isEmpty());
        assertEquals(Notifier.Urgency.PAGE, notifications.get(0).urgency());
    }

    @Test
    void executedActionsCanBeRevertedWhichRunsTheInverse() throws Exception {
        String id = engine.handle(badDeploy(0.95)).actionId();
        RemediationOutcome r = engine.revert(id, "carol");
        assertEquals(AuditRecord.Status.REVERTED, r.status());
        assertEquals(new RemediationAction.Rollback("pool-llama-70b", "v42", "v43"), executed.get(1));
        assertEquals(List.of(AuditRecord.Status.EXECUTED, AuditRecord.Status.REVERTED),
                audit.history(id).stream().map(AuditRecord::status).toList());
    }

    @Test
    void guardrailSuppressesASecondAutomatedActionOnTheSameTarget() {
        engine.handle(badDeploy(0.95));
        clock.advance(Duration.ofMinutes(1));
        RemediationOutcome second = engine.handle(badDeploy(0.95));
        assertEquals(AuditRecord.Status.SUPPRESSED, second.status());
        assertEquals(1, executed.size());
        assertEquals(Notifier.Urgency.PAGE, notifications.get(notifications.size() - 1).urgency());
    }

    @Test
    void hypothesesWithoutAPlaybookEscalate() {
        Hypothesis h = new Hypothesis(Hypothesis.Kind.BAD_CHANGE, "pool", Optional.empty(), 0.99, List.of());
        RemediationOutcome o = engine.handle(h);
        assertEquals(AuditRecord.Status.ESCALATED, o.status());
        assertTrue(o.action().isEmpty());
    }

    @Test
    void executorFailureIsRecordedNotSwallowed() {
        engine = new RemediationEngine(RemediationPlanner.defaults(FLEET), ConfidencePolicy.defaults(),
                OscillationGuard.defaults(clock), a -> {
                    throw new RemediationException("k8s API unavailable");
                }, notifications::add, audit, clock);
        RemediationOutcome o = engine.handle(badDeploy(0.95));
        assertEquals(AuditRecord.Status.FAILED, o.status());
        assertEquals(Notifier.Urgency.PAGE, notifications.get(notifications.size() - 1).urgency());
    }
}
