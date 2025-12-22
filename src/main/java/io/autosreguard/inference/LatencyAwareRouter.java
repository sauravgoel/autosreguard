package io.autosreguard.inference;

import io.autosreguard.core.Preconditions;
import io.autosreguard.core.Segment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Routes each request to the best model variant that can meet its latency budget, degrading
 * gracefully under pressure instead of failing.
 *
 * <h2>Selection</h2>
 * Variants are tried from highest to lowest quality (the <em>degradation ladder</em>). A
 * variant is skipped when:
 * <ol>
 *   <li>its quality is below the request's {@code minQuality},</li>
 *   <li>the request does not fit its context window,</li>
 *   <li>its circuit breaker is open,</li>
 *   <li>its pool is saturated (pressure >= 1),</li>
 *   <li>the request's segment is degradable and pool pressure exceeds {@code degradeAtPressure},</li>
 *   <li>the live-estimated latency exceeds the request's budget.</li>
 * </ol>
 * Rule 5 is the reliability/cost lever: consumer traffic moves to smaller models early so
 * enterprise traffic keeps the flagship even as the pool heats up.
 *
 * <p>What is budgeted depends on the request: streaming clients feel TTFT, so TTFT is
 * compared to the budget; non-streaming clients wait for the whole response, so
 * TTFT + TPOT x maxOutputTokens is compared.
 */
public final class LatencyAwareRouter {

    private final List<ModelVariant> ladder;
    private final Map<String, CircuitBreaker> breakers;
    private final LiveLatencyModel latency;
    private final LoadShedder shedder;
    private final double degradeAtPressure;

    /**
     * @param variants          available variants (any order; sorted by quality internally)
     * @param breakers          one breaker per variant name
     * @param latency           live latency/pressure model
     * @param shedder           admission control applied before routing
     * @param degradeAtPressure pressure above which degradable segments are moved down the ladder
     */
    public LatencyAwareRouter(List<ModelVariant> variants, Map<String, CircuitBreaker> breakers,
                              LiveLatencyModel latency, LoadShedder shedder, double degradeAtPressure) {
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("at least one variant required");
        }
        this.ladder = variants.stream()
                .sorted(Comparator.comparingDouble(ModelVariant::qualityScore).reversed())
                .toList();
        this.breakers = Map.copyOf(breakers);
        for (ModelVariant v : ladder) {
            if (!this.breakers.containsKey(v.name())) {
                throw new IllegalArgumentException("missing circuit breaker for " + v.name());
            }
        }
        this.latency = Objects.requireNonNull(latency, "latency");
        this.shedder = Objects.requireNonNull(shedder, "shedder");
        this.degradeAtPressure = Preconditions.checkFraction(degradeAtPressure, "degradeAtPressure");
    }

    public RoutingDecision route(InferenceRequest req) {
        if (!shedder.admit(req)) {
            return new RoutingDecision.Rejected(List.of(String.format(Locale.ROOT,
                    "shed: %s traffic at %.0f%% shedding", req.segment(),
                    shedder.sheddingFractionFor(req.segment()) * 100)));
        }
        List<String> notes = new ArrayList<>();
        boolean skippedEligible = false;
        for (ModelVariant v : ladder) {
            if (v.qualityScore() < req.minQuality()) {
                notes.add(v.name() + ": below minQuality");
                continue;
            }
            if (req.totalTokens() > v.maxContextTokens()) {
                notes.add(v.name() + ": context too small");
                continue;
            }
            // From here on, v is a variant the request could legitimately have used.
            if (!breakers.get(v.name()).isCallPermitted()) {
                notes.add(v.name() + ": circuit open");
                skippedEligible = true;
                continue;
            }
            double pressure = latency.pressure(v.name());
            if (pressure >= 1.0) {
                notes.add(String.format(Locale.ROOT, "%s: saturated (pressure %.2f)", v.name(), pressure));
                skippedEligible = true;
                continue;
            }
            if (isDegradable(req.segment()) && pressure > degradeAtPressure && !isLast(v)) {
                notes.add(String.format(Locale.ROOT, "%s: pressure %.2f > %.2f, degrading %s",
                        v.name(), pressure, degradeAtPressure, req.segment()));
                skippedEligible = true;
                continue;
            }
            double est = estimate(v, req);
            if (est > req.latencyBudgetMs()) {
                notes.add(String.format(Locale.ROOT, "%s: est %.0fms > budget %.0fms", v.name(), est, req.latencyBudgetMs()));
                skippedEligible = true;
                continue;
            }
            return new RoutingDecision.Routed(v, skippedEligible, est, notes);
        }
        return new RoutingDecision.Rejected(notes);
    }

    /** Estimated latency comparable to the request's budget (see class docs). */
    public double estimate(ModelVariant v, InferenceRequest req) {
        double ttft = latency.estimateTtftMs(v, req.promptTokens());
        return req.streaming() ? ttft : ttft + latency.estimateTpotMs(v) * req.maxOutputTokens();
    }

    /** Only consumer traffic is proactively degraded; others degrade only when forced. */
    private static boolean isDegradable(Segment s) {
        return s == Segment.CONSUMER;
    }

    private boolean isLast(ModelVariant v) {
        return ladder.get(ladder.size() - 1).equals(v);
    }

    public List<ModelVariant> ladder() {
        return ladder;
    }
}
