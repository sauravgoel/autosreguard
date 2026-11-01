package io.autosreguard.slo;

import io.autosreguard.core.TenantId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks SLOs per segment AND per tenant, and evaluates multi-window burn-rate alerts.
 *
 * <h2>The problem this solves</h2>
 * A single availability number across all users is mathematically guaranteed to obscure
 * your most important users. This tracker keeps two levels of counters for every SLO:
 * <ol>
 *   <li>one per <b>segment</b> (enterprise / API / consumer), and</li>
 *   <li>one per <b>tenant</b> within that segment.</li>
 * </ol>
 * When a tenant's SLO is burning but its segment's is not, the resulting {@link SloStatus}
 * is flagged {@code maskedByAggregate}. That is precisely the "you are still at 99.999%,
 * your dashboards are green, and you're about to lose a major customer" situation, and it
 * should be routed to the account's escalation path, not just the service on-call.
 *
 * <h2>Scope of responsibility</h2>
 * This class is a real-time alerting engine with a bounded horizon (the policy's longest
 * window). Long-period compliance reporting (28/30-day) belongs in your time-series database.
 *
 * <h2>Bounded tenant cardinality</h2>
 * Tenant counters idle for the whole horizon are evicted by {@link #evaluate()}, and the
 * number of tenant series is capped. Once the cap is reached, outcomes from tenants that are
 * not yet tracked still count toward their segment's SLO but get no tenant-level series;
 * they are counted in {@link #untrackedTenantOutcomes()} so the condition can be alerted on.
 */
public final class SegmentedSloTracker {

    private final List<SloDefinition> slos;
    private final BurnRatePolicy policy;
    private final Clock clock;
    private final Duration horizon;
    private final Duration bucketWidth;
    private final int maxTenantSeries;

    /** Default cap: ~345 MiB of tenant counters at 1m buckets over a 24h horizon. */
    public static final int DEFAULT_MAX_TENANT_SERIES = 10_000;

    /** Outcomes that got no tenant series because the cap was reached. */
    private final LongAdder untrackedTenantOutcomes = new LongAdder();

    /** Segment-level counter per SLO name. */
    private final Map<String, SlidingWindowCounter> segmentCounters = new ConcurrentHashMap<>();

    /** Tenant-level counters: SLO name -> tenant -> counter. */
    private final Map<String, Map<TenantId, SlidingWindowCounter>> tenantCounters = new ConcurrentHashMap<>();

    /** Creates a tracker capped at {@link #DEFAULT_MAX_TENANT_SERIES} tenant series. */
    public SegmentedSloTracker(List<SloDefinition> slos, BurnRatePolicy policy, Duration bucketWidth, Clock clock) {
        this(slos, policy, bucketWidth, DEFAULT_MAX_TENANT_SERIES, clock);
    }

    /**
     * @param slos            objectives to track
     * @param policy          burn-rate alert policy
     * @param bucketWidth     resolution of counters; must be well under the policy's shortest window
     * @param maxTenantSeries cap on tenant series across all SLOs; a soft limit that concurrent
     *                        first-time tenants may exceed by a few series
     * @param clock           time source
     */
    public SegmentedSloTracker(List<SloDefinition> slos, BurnRatePolicy policy, Duration bucketWidth,
                               int maxTenantSeries, Clock clock) {
        this.slos = List.copyOf(Objects.requireNonNull(slos, "slos"));
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.bucketWidth = Objects.requireNonNull(bucketWidth, "bucketWidth");
        this.horizon = policy.longestWindow();
        if (maxTenantSeries <= 0) {
            throw new IllegalArgumentException("maxTenantSeries must be positive: " + maxTenantSeries);
        }
        this.maxTenantSeries = maxTenantSeries;
        long distinctNames = this.slos.stream().map(SloDefinition::name).distinct().count();
        if (distinctNames != this.slos.size()) {
            throw new IllegalArgumentException("SLO names must be unique");
        }
        Duration shortest = policy.rules().stream().map(BurnRatePolicy.Rule::shortWindow)
                .min(Comparator.naturalOrder()).orElseThrow();
        if (bucketWidth.multipliedBy(2).compareTo(shortest) > 0) {
            throw new IllegalArgumentException("bucketWidth " + bucketWidth
                    + " is too coarse for shortest window " + shortest);
        }
        for (SloDefinition slo : this.slos) {
            segmentCounters.put(slo.name(), newCounter());
            tenantCounters.put(slo.name(), new ConcurrentHashMap<>());
        }
    }

    private SlidingWindowCounter newCounter() {
        return new SlidingWindowCounter(horizon, bucketWidth);
    }

    /** Feeds one outcome into every SLO that applies to it. Hot path: allocation-light. */
    public void record(InferenceOutcome outcome) {
        for (SloDefinition slo : slos) {
            if (!slo.appliesTo(outcome)) {
                continue;
            }
            boolean good = slo.isGood(outcome);
            segmentCounters.get(slo.name()).record(outcome.timestamp(), good);
            Map<TenantId, SlidingWindowCounter> tenants = tenantCounters.get(slo.name());
            SlidingWindowCounter c = tenants.get(outcome.tenant());
            if (c == null) {
                if (trackedTenantSeries() >= maxTenantSeries) {
                    untrackedTenantOutcomes.increment();
                    continue;
                }
                c = tenants.computeIfAbsent(outcome.tenant(), t -> newCounter());
            }
            c.record(outcome.timestamp(), good);
        }
    }

    /**
     * Evaluates every SLO at every scope, returning statuses sorted most-severe first.
     * Tenant statuses that fire while their segment does not are marked as masked.
     *
     * <p>Tenant counters with no events inside the horizon can no longer contribute to any
     * window, so they are evicted here instead of being scanned and reported forever. This
     * keeps memory and evaluation cost proportional to recently active tenants, not to every
     * tenant ever seen. An outcome recorded concurrently with the eviction of its tenant's
     * counter may be dropped; that only affects a tenant idle for the entire horizon.
     */
    public List<SloStatus> evaluate() {
        Instant now = clock.instant();
        List<SloStatus> out = new ArrayList<>();
        for (SloDefinition slo : slos) {
            SloStatus segStatus = evaluateCounter(slo, new Scope.SegmentScope(slo.segment()),
                    segmentCounters.get(slo.name()), now);
            out.add(segStatus);
            Map<TenantId, SlidingWindowCounter> tenants = tenantCounters.get(slo.name());
            for (Map.Entry<TenantId, SlidingWindowCounter> e : tenants.entrySet()) {
                if (e.getValue().sum(horizon, now).total() == 0) {
                    // Re-check under the map's lock so a counter refilled in the meantime is kept.
                    tenants.computeIfPresent(e.getKey(),
                            (k, c) -> c.sum(horizon, now).total() == 0 ? null : c);
                    continue;
                }
                SloStatus t = evaluateCounter(slo, new Scope.TenantScope(slo.segment(), e.getKey()), e.getValue(), now);
                // The key insight of segmented SLOs, expressed in one line:
                boolean masked = t.firing() && !segStatus.severity().atLeast(t.severity());
                out.add(masked ? t.withMasked(true) : t);
            }
        }
        out.sort(Comparator.comparing(SloStatus::severity).reversed()
                .thenComparing(SloStatus::maskedByAggregate, Comparator.reverseOrder())
                .thenComparingDouble(SloStatus::budgetRemaining));
        return out;
    }

    /** Only the statuses that are currently firing. */
    public List<SloStatus> firing() {
        return evaluate().stream().filter(SloStatus::firing).toList();
    }

    private SloStatus evaluateCounter(SloDefinition slo, Scope scope, SlidingWindowCounter c, Instant now) {
        SlidingWindowCounter.Counts horizonCounts = c.sum(horizon, now);
        double budget = slo.errorBudget();
        double budgetRemaining = 1.0 - (horizonCounts.errorRate() / budget);

        // Rules are pre-sorted most severe first; the first one that fires wins.
        for (BurnRatePolicy.Rule rule : policy.rules()) {
            SlidingWindowCounter.Counts longC = c.sum(rule.longWindow(), now);
            if (longC.total() < policy.minEvents()) {
                continue; // not statistically meaningful yet
            }
            SlidingWindowCounter.Counts shortC = c.sum(rule.shortWindow(), now);
            double longBurn = longC.errorRate() / budget;
            double shortBurn = shortC.errorRate() / budget;
            if (longBurn > rule.threshold() && shortBurn > rule.threshold()) {
                return new SloStatus(slo, scope, horizonCounts.total(), horizonCounts.errorRate(), budgetRemaining,
                        rule.severity(), rule.describe(), longBurn, shortBurn, false);
            }
        }
        // Nothing firing: report the fastest rule's burn rates for dashboards.
        BurnRatePolicy.Rule fastest = policy.rules().get(0);
        double longBurn = c.sum(fastest.longWindow(), now).errorRate() / budget;
        double shortBurn = c.sum(fastest.shortWindow(), now).errorRate() / budget;
        return new SloStatus(slo, scope, horizonCounts.total(), horizonCounts.errorRate(), budgetRemaining,
                AlertSeverity.NONE, "", longBurn, shortBurn, false);
    }

    public List<SloDefinition> slos() {
        return slos;
    }

    /** Number of tenants currently tracked across all SLOs (a cardinality health metric). */
    public int trackedTenantSeries() {
        return tenantCounters.values().stream().mapToInt(Map::size).sum();
    }

    /** Total outcomes that were not tracked per tenant because the series cap was reached. */
    public long untrackedTenantOutcomes() {
        return untrackedTenantOutcomes.sum();
    }
}
