package io.autosreguard.demo;

import io.autosreguard.config.AutoSREGuardConfig;
import io.autosreguard.core.Clocks;
import io.autosreguard.detection.EwmaAnomalyDetector;
import io.autosreguard.detection.Signals;
import io.autosreguard.diagnosis.ChangeEvent;
import io.autosreguard.diagnosis.ChangeLog;
import io.autosreguard.diagnosis.ServiceGraph;
import io.autosreguard.remediation.AuditLog;
import io.autosreguard.remediation.AuditRecord;
import io.autosreguard.remediation.Notifier;
import io.autosreguard.remediation.RemediationOutcome;
import io.autosreguard.runtime.ControlCycleReport;
import io.autosreguard.runtime.AutoSREGuard;
import io.autosreguard.runtime.AutoSREGuardFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SplittableRandom;

/**
 * Autonomous SRE end-to-end: a bad model-server deploy is detected, diagnosed as the root cause
 * (with the gateway and router correctly classified as symptoms), and rolled back automatically.
 * A second bad change minutes later hits the oscillation guard and is escalated instead.
 */
final class BadDeployScenario implements Scenario {

    static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    @Override
    public String name() {
        return "bad-deploy";
    }

    @Override
    public void run(Console c) throws Exception {
        c.banner("Scenario 2: Detect -> diagnose -> remediate in seconds",
                "automate the repetitive incidents; guardrails stop remediation loops");

        Clocks.ManualClock clock = Clocks.manual(Instant.parse("2026-04-17T14:00:00Z"));
        ServiceGraph graph = new ServiceGraph()
                .dependsOn("api-gateway", "inference-router")
                .dependsOn("inference-router", "pool-llama-70b")
                .dependsOn("inference-router", "pool-llama-8b");
        ChangeLog changes = new ChangeLog(1000);
        SimulatedFleet fleet = new SimulatedFleet();
        fleet.versions.put("pool-llama-70b", "v42");
        fleet.versions.put("pool-llama-70b:max_batch_tokens", "8192");

        Properties p = new Properties();
        p.setProperty("remediation.dry-run", "false");
        AuditLog audit = AuditLog.inMemory(1000);
        Notifier notifier = n -> c.event(HHMMSS.format(clock.instant()), "NOTIFY %-18s %s", n.urgency(), n.title());
        AutoSREGuard ig = AutoSREGuardFactory.create(AutoSREGuardConfig.of(p, Map.of()), clock, graph, changes, fleet,
                fleet, notifier, audit);

        List<String> components = List.of("api-gateway", "inference-router", "pool-llama-70b", "pool-llama-8b");
        for (String comp : components) {
            ig.registerDetector(EwmaAnomalyDetector.builder(comp, Signals.ERROR_RATE)
                    .minSigma(0.0005).build());
        }

        SplittableRandom rnd = new SplittableRandom(7);
        c.section("Timeline (30s samples; baseline error rate ~0.2%)");
        for (int step = 0; step <= 64; step++) {
            Instant now = clock.instant();
            String ts = HHMMSS.format(now);
            if (step == 40) {
                changes.record(new ChangeEvent("deploy-8812", ChangeEvent.Kind.DEPLOYMENT, "pool-llama-70b",
                        "v42", "v43", now, "ci-pipeline"));
                fleet.versions.put("pool-llama-70b", "v43");
                c.event(ts, "CHANGE  ci-pipeline deploys pool-llama-70b v42 -> v43 (has a KV-cache allocator bug)");
            }
            if (step == 46) {
                changes.record(new ChangeEvent("max_batch_tokens", ChangeEvent.Kind.CONFIG, "pool-llama-70b",
                        "8192", "32768", now, "alice@oncall"));
                fleet.versions.put("pool-llama-70b:max_batch_tokens", "32768");
                c.event(ts, "CHANGE  alice raises pool-llama-70b max_batch_tokens 8192 -> 32768 (causes GPU OOMs)");
            }
            boolean badVersion = "v43".equals(fleet.version("pool-llama-70b"));
            boolean badConfig = "32768".equals(fleet.version("pool-llama-70b:max_batch_tokens"));
            double poolErr = badVersion || badConfig ? 0.08 : 0.002;
            double noise = rnd.nextDouble(-0.0005, 0.0005);
            ig.observeSignal("pool-llama-70b", Signals.ERROR_RATE, now, poolErr + noise);
            ig.observeSignal("pool-llama-8b", Signals.ERROR_RATE, now, 0.002 + rnd.nextDouble(-0.0005, 0.0005));
            // Symptoms propagate upstream, diluted by healthy traffic to the 8b pool.
            ig.observeSignal("inference-router", Signals.ERROR_RATE, now, poolErr * 0.6 + noise);
            ig.observeSignal("api-gateway", Signals.ERROR_RATE, now, poolErr * 0.5 + noise);

            ControlCycleReport r = ig.runCycle();
            if (!r.outcomes().isEmpty() && !r.hypotheses().isEmpty()) {
                c.event(ts, "DIAGNOSE top hypotheses:");
                r.hypotheses().stream().limit(3).forEach(h -> c.line("           %.2f  %-20s %-16s %s",
                        h.confidence(), h.kind(), h.subject(),
                        h.change().map(ch -> ch.kind() + " " + ch.id()).orElse("")));
            }
            for (RemediationOutcome o : r.outcomes()) {
                c.event(ts, "DECIDE  %s -> %s: %s (%s)", o.tier(), o.status(),
                        o.action().map(a -> a.describe()).orElse("-"), o.message());
            }
            clock.advance(Duration.ofSeconds(30));
        }

        c.section("Audit trail (logged, explainable, reversible)");
        for (AuditRecord rec : audit.recent()) {
            c.line("#%d %s %-10s %-17s conf=%.2f action=\"%s\" inverse=\"%s\" note=\"%s\"",
                    rec.seq(), rec.actionId(), rec.status(), rec.tier(), rec.confidence(), rec.action(),
                    rec.inverse(), rec.note());
        }
        c.line("Fleet actions applied: %s", fleet.actionLog);
        c.takeaway("Rollback ran ~60s after onset with no human. The second action was held by the "
                + "cooldown guardrail, a human was paged, and it ran only after the cooldown elapsed.");
    }
}
