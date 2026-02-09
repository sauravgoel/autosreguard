package io.autosreguard.runtime;

import io.autosreguard.detection.Anomaly;
import io.autosreguard.detection.AnomalyDetector;
import io.autosreguard.detection.EwmaAnomalyDetector;
import io.autosreguard.diagnosis.Hypothesis;
import io.autosreguard.diagnosis.RootCauseAnalyzer;
import io.autosreguard.gpu.GpuHealthScorer;
import io.autosreguard.gpu.GpuTelemetry;
import io.autosreguard.metrics.MetricsRegistry;
import io.autosreguard.remediation.AuditRecord;
import io.autosreguard.remediation.RemediationEngine;
import io.autosreguard.remediation.RemediationOutcome;
import io.autosreguard.slo.InferenceOutcome;
import io.autosreguard.slo.SegmentedSloTracker;
import io.autosreguard.slo.SloStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The autonomous SRE control loop: <b>detect → diagnose → remediate</b>, in seconds.
 *
 * <p>Each {@link #runCycle()}:
 * <ol>
 *   <li>Evaluates segmented SLOs (what users feel; used for reporting and paging context).</li>
 *   <li>Collects active anomalies from signal detectors and GPU health scoring.</li>
 *   <li>Runs root-cause analysis over them.</li>
 *   <li>Hands the top hypothesis above {@code minHypothesisConfidence} to the remediation engine,
 *       unless the same (kind, subject) was handled recently.</li>
 *   <li>Ticks the engine so expired veto windows execute.</li>
 *   <li>Publishes metrics about all of the above.</li>
 * </ol>
 *
 * <p>Telemetry ingestion ({@link #recordOutcome}, {@link #observeSignal}, {@link #observeGpu}) is
 * thread-safe and may be called from many threads. The cycle itself runs on one thread.
 */
public final class AutoSREGuard implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(AutoSREGuard.class.getName());

    private final SegmentedSloTracker slos;
    private final RootCauseAnalyzer analyzer;
    private final RemediationEngine engine;
    private final GpuHealthScorer gpuHealth;
    private final MetricsRegistry metrics;
    private final Clock clock;
    private final Duration anomalyTtl;
    private final Duration rehandleAfter;
    private final double minHypothesisConfidence;

    /** component/signal -> detector */
    private final Map<String, AnomalyDetector> detectors = new ConcurrentHashMap<>();
    /** component/signal -> most recent anomaly (expires after anomalyTtl) */
    private final Map<String, Anomaly> active = new ConcurrentHashMap<>();
    /** kind:subject[:changeId] -> last handled time (incident de-duplication) */
    private final Map<String, Instant> handled = new HashMap<>();
    private volatile List<SloStatus> lastStatuses = List.of();

    private final MetricsRegistry.Counter cycles;
    private ScheduledExecutorService scheduler;

    public AutoSREGuard(SegmentedSloTracker slos, RootCauseAnalyzer analyzer, RemediationEngine engine,
                      GpuHealthScorer gpuHealth, MetricsRegistry metrics, Clock clock,
                      Duration anomalyTtl, Duration rehandleAfter, double minHypothesisConfidence) {
        this.slos = Objects.requireNonNull(slos, "slos");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.gpuHealth = Objects.requireNonNull(gpuHealth, "gpuHealth");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.anomalyTtl = Objects.requireNonNull(anomalyTtl, "anomalyTtl");
        this.rehandleAfter = Objects.requireNonNull(rehandleAfter, "rehandleAfter");
        this.minHypothesisConfidence = minHypothesisConfidence;
        this.cycles = metrics.counter("autosreguard_control_cycles_total", "Control loop iterations", Map.of());
        metrics.gauge("autosreguard_active_anomalies", "Anomalies currently active", Map.of(), active::size);
        metrics.gauge("autosreguard_tracked_tenant_series", "Per-tenant SLO series tracked", Map.of(),
                slos::trackedTenantSeries);
    }

    // ---- Ingestion -------------------------------------------------------------------

    public void recordOutcome(InferenceOutcome o) {
        slos.record(o);
    }

    public void registerDetector(EwmaAnomalyDetector d) {
        detectors.put(key(d.component(), d.signal()), d);
    }

    /** Feeds a sample to the matching detector; unknown signals are ignored (not an error). */
    public void observeSignal(String component, String signal, Instant at, double value) {
        AnomalyDetector d = detectors.get(key(component, signal));
        if (d != null) {
            d.observe(at, value).ifPresent(a -> active.put(key(component, signal), a));
        }
    }

    public void observeGpu(GpuTelemetry t) {
        gpuHealth.observe(t);
        gpuHealth.toAnomaly(t.nodeId()).ifPresent(a -> active.put(key(a.component(), a.signal()), a));
    }

    // ---- Control loop ----------------------------------------------------------------

    public synchronized ControlCycleReport runCycle() {
        Instant now = clock.instant();
        cycles.inc();

        List<SloStatus> statuses = slos.evaluate();
        lastStatuses = statuses;
        List<SloStatus> firing = statuses.stream().filter(SloStatus::firing).toList();
        publishSloMetrics(statuses);

        active.values().removeIf(a -> Duration.between(a.detectedAt(), now).compareTo(anomalyTtl) > 0);
        List<Anomaly> anomalies = List.copyOf(active.values());
        List<Hypothesis> hypotheses = analyzer.analyze(anomalies);

        List<RemediationOutcome> outcomes = new ArrayList<>();
        for (Hypothesis h : hypotheses) {
            if (h.confidence() < minHypothesisConfidence) {
                break; // sorted by confidence
            }
            // Incident identity includes the implicated change, so a second bad change on the same
            // component is a new incident (and will meet the oscillation guard, not the de-dup).
            String incident = h.kind() + ":" + h.subject() + h.change().map(c -> ":" + c.id()).orElse("");
            Instant last = handled.get(incident);
            if (last != null && Duration.between(last, now).compareTo(rehandleAfter) < 0) {
                continue;
            }
            handled.put(incident, now);
            RemediationOutcome o = engine.handle(h);
            outcomes.add(o);
            count(o);
            if (o.status() == AuditRecord.Status.EXECUTED) {
                // We just changed the system. Clear ALL active anomalies, including downstream
                // symptoms, so the next cycle judges fresh data. Otherwise a stale symptom on a
                // dependent service looks like a new root cause and triggers a spurious second
                // action. Problems that persist re-surface within one detector sample.
                active.clear();
            }
            break; // one decision per cycle: let the effect of an action show before the next
        }
        for (RemediationOutcome o : engine.tick()) {
            outcomes.add(o);
            count(o);
            if (o.status() == AuditRecord.Status.EXECUTED) {
                // A proposal whose veto window elapsed changes the system exactly like an
                // auto-remediation does, so the same "judge fresh data" rule applies. Without
                // this, the anomaly that motivated the proposal lingers for its TTL and gets
                // re-diagnosed as if nothing had been done.
                active.clear();
            }
        }
        return new ControlCycleReport(now, firing, anomalies, hypotheses, outcomes);
    }

    private void count(RemediationOutcome o) {
        metrics.counter("autosreguard_remediations_total", "Remediation decisions by tier and status",
                Map.of("tier", o.tier().name(), "status", o.status().name())).inc();
    }

    private void publishSloMetrics(List<SloStatus> statuses) {
        for (SloStatus s : statuses) {
            // Segment series always; tenant series only while firing (cardinality control).
            boolean segment = s.scope() instanceof io.autosreguard.slo.Scope.SegmentScope;
            Map<String, String> labels = Map.of("slo", s.slo().name(), "scope", s.scope().label());
            if (segment || s.firing()) {
                double budget = s.budgetRemaining();
                double burn = s.longBurnRate();
                metrics.gauge("autosreguard_slo_error_budget_remaining", "Fraction of error budget left", labels, () -> budget);
                metrics.gauge("autosreguard_slo_burn_rate", "Long-window burn rate", labels, () -> burn);
            } else {
                metrics.removeGauge("autosreguard_slo_error_budget_remaining", labels);
                metrics.removeGauge("autosreguard_slo_burn_rate", labels);
            }
        }
    }

    /** Starts the loop on a single daemon thread. Exceptions are logged, never fatal. */
    public synchronized void start(Duration interval) {
        if (scheduler != null) {
            throw new IllegalStateException("already started");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "autosreguard-control-loop");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                ControlCycleReport r = runCycle();
                if (!r.outcomes().isEmpty()) {
                    LOG.log(System.Logger.Level.INFO, "cycle outcomes: {0}", r.outcomes());
                }
            } catch (RuntimeException e) {
                // A crashing control loop is itself an incident; keep running and make noise.
                LOG.log(System.Logger.Level.ERROR, "control cycle failed", e);
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public List<SloStatus> lastStatuses() {
        return lastStatuses;
    }

    public RemediationEngine engine() {
        return engine;
    }

    public MetricsRegistry metrics() {
        return metrics;
    }

    public GpuHealthScorer gpuHealth() {
        return gpuHealth;
    }

    private static String key(String component, String signal) {
        return component + "/" + signal;
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
