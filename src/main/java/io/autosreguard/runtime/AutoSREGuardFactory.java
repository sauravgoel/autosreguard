package io.autosreguard.runtime;

import io.autosreguard.config.AutoSREGuardConfig;
import io.autosreguard.core.Segment;
import io.autosreguard.diagnosis.ChangeLog;
import io.autosreguard.diagnosis.RootCauseAnalyzer;
import io.autosreguard.diagnosis.ServiceGraph;
import io.autosreguard.gpu.GpuHealthScorer;
import io.autosreguard.metrics.MetricsRegistry;
import io.autosreguard.remediation.ActionExecutor;
import io.autosreguard.remediation.AuditLog;
import io.autosreguard.remediation.ChangeRecordingExecutor;
import io.autosreguard.remediation.ConfidencePolicy;
import io.autosreguard.remediation.FleetState;
import io.autosreguard.remediation.Notifier;
import io.autosreguard.remediation.OscillationGuard;
import io.autosreguard.remediation.RemediationEngine;
import io.autosreguard.remediation.RemediationPlanner;
import io.autosreguard.slo.BurnRatePolicy;
import io.autosreguard.slo.SegmentedSloTracker;
import io.autosreguard.slo.SloDefinition;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles an {@link AutoSREGuard} from configuration. Keeping construction in one place
 * (instead of a DI framework) makes the object graph explicit and easy to read in review.
 */
public final class AutoSREGuardFactory {

    private AutoSREGuardFactory() {
    }

    /**
     * Default segmented SLOs for an LLM serving platform, overridable per key in config:
     * availability for every segment, plus TTFT for interactive-heavy segments.
     */
    public static List<SloDefinition> defaultSlos(AutoSREGuardConfig c) {
        List<SloDefinition> l = new ArrayList<>();
        for (Segment s : Segment.values()) {
            String seg = s.name().toLowerCase(java.util.Locale.ROOT);
            l.add(SloDefinition.availability(seg + "-availability", s,
                    c.dbl("slo." + seg + ".availability", s.defaultAvailabilityTarget())));
        }
        l.add(SloDefinition.ttft("enterprise-ttft", Segment.ENTERPRISE,
                c.dbl("slo.enterprise.ttft.target", 0.99), c.dbl("slo.enterprise.ttft.threshold-ms", 800)));
        l.add(SloDefinition.ttft("consumer-ttft", Segment.CONSUMER,
                c.dbl("slo.consumer.ttft.target", 0.95), c.dbl("slo.consumer.ttft.threshold-ms", 1500)));
        return l;
    }

    /**
     * Builds a runtime.
     *
     * @param c        configuration
     * @param clock    time source
     * @param graph    service dependency graph
     * @param changes  change log fed by CI/CD
     * @param fleet    fleet state view for the planner
     * @param executor action executor (wrapped in dry-run if configured)
     * @param notifier human notification channel
     * @param audit    audit log
     */
    public static AutoSREGuard create(AutoSREGuardConfig c, Clock clock, ServiceGraph graph, ChangeLog changes,
                                    FleetState fleet, ActionExecutor executor, Notifier notifier, AuditLog audit) {
        BurnRatePolicy defaults = BurnRatePolicy.defaults();
        BurnRatePolicy policy = new BurnRatePolicy(defaults.rules(), c.sloMinEvents());
        SegmentedSloTracker tracker = new SegmentedSloTracker(defaultSlos(c), policy, c.sloBucketWidth(), clock);

        RootCauseAnalyzer analyzer = new RootCauseAnalyzer(graph, changes, Duration.ofMinutes(30), Duration.ofMinutes(10));
        ConfidencePolicy confidence = new ConfidencePolicy(c.autoThreshold(), c.suggestThreshold(), c.vetoWindow());
        OscillationGuard guard = new OscillationGuard(clock, c.cooldown(), Duration.ofMinutes(30),
                Duration.ofMinutes(10), c.maxActionsPer10m());
        // Record our own rollbacks/reverts in the change log so diagnosis sees what is live.
        // In dry-run nothing actually changes, so nothing is recorded.
        ActionExecutor effective = c.dryRun()
                ? ActionExecutor.dryRun()
                : new ChangeRecordingExecutor(executor, changes, clock);
        RemediationEngine engine = new RemediationEngine(RemediationPlanner.defaults(fleet), confidence, guard,
                effective, notifier, audit, clock);

        return new AutoSREGuard(tracker, analyzer, engine, GpuHealthScorer.defaults(), new MetricsRegistry(), clock,
                Duration.ofMinutes(10), c.cooldown(), 0.3);
    }
}
