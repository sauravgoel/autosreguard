package io.autosreguard.demo;

import io.autosreguard.core.Clocks;
import io.autosreguard.core.Segment;
import io.autosreguard.core.TenantId;
import io.autosreguard.inference.CircuitBreaker;
import io.autosreguard.inference.InferenceRequest;
import io.autosreguard.inference.LatencyAwareRouter;
import io.autosreguard.inference.LiveLatencyModel;
import io.autosreguard.inference.LoadShedder;
import io.autosreguard.inference.ModelVariant;
import io.autosreguard.inference.PrefixAffinityBalancer;
import io.autosreguard.inference.RoutingDecision;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Under capacity pressure, serve a smaller, faster model variant instead of an error. Also
 * demonstrates two related GenAI serving levers: priority-aware load shedding and
 * KV/prefix-cache-aware routing.
 *
 * <p>Three acts:
 * <ol>
 *   <li>Pressure ramps on the flagship pool. Consumer traffic steps down the ladder first;
 *       enterprise keeps the flagship until its latency budget is genuinely at risk.</li>
 *   <li>The flagship's circuit breaker trips. Nobody gets an error: enterprise lands on the
 *       quantized variant, flagged as degraded.</li>
 *   <li>Shedding and prefix-affinity: who gets admitted at a given shedding level, and why
 *       cache-affinity beats least-loaded for prefix-cache hit rate without hot-spotting.</li>
 * </ol>
 */
final class GracefulDegradationScenario implements Scenario {

    static final ModelVariant FP16 = new ModelVariant("llama-70b-fp16", 0.92, 350, 120, 28, 32_768, 1.0);
    static final ModelVariant INT8 = new ModelVariant("llama-70b-int8", 0.90, 260, 90, 18, 32_768, 0.55);
    static final ModelVariant SMALL = new ModelVariant("llama-8b", 0.78, 90, 30, 7, 16_384, 0.12);

    @Override
    public String name() {
        return "graceful-degradation";
    }

    @Override
    public void run(Console c) {
        c.banner("Scenario 5: Degrade, don't fail",
                "under pressure, serve a smaller model before you serve an error");

        Clocks.ManualClock clock = Clocks.manual(Instant.parse("2026-04-17T18:00:00Z"));
        Map<String, CircuitBreaker> breakers = new HashMap<>();
        for (ModelVariant v : List.of(FP16, INT8, SMALL)) {
            breakers.put(v.name(), CircuitBreaker.defaults(v.name(), clock));
        }
        LiveLatencyModel latency = new LiveLatencyModel(0.2);
        LoadShedder shedder = new LoadShedder();
        LatencyAwareRouter router = new LatencyAwareRouter(List.of(FP16, INT8, SMALL), breakers, latency,
                shedder, 0.7);

        // One representative request per segment. Budgets reflect what each segment feels:
        //  - enterprise chat: streaming, generous TTFT budget, must stay on a high-quality model
        //  - consumer chat:   streaming, tighter TTFT budget, any quality is acceptable
        //  - API batch:       non-streaming, end-to-end budget over 200 output tokens
        InferenceRequest enterprise = new InferenceRequest("ent-1", TenantId.of("acme-corp"), Segment.ENTERPRISE,
                2_000, 400, 2_500, true, "sys-acme-v3", 0.85);
        InferenceRequest consumer = new InferenceRequest("con-1", TenantId.of("consumer"), Segment.CONSUMER,
                1_000, 300, 1_500, true, "sys-chat-v9", 0.0);
        InferenceRequest api = new InferenceRequest("api-1", TenantId.of("dev-shop"), Segment.API,
                1_500, 200, 8_000, false, "", 0.0);

        c.section("Act 1: pressure ramps on the flagship pool (degrade threshold 0.70)");
        c.line("%-9s | %-34s | %-34s | %-34s", "pressure", "ENTERPRISE (min quality .85)", "CONSUMER", "API");
        latency.setPressure(INT8.name(), 0.3);
        latency.setPressure(SMALL.name(), 0.2);
        for (double p : new double[] {0.2, 0.5, 0.75, 0.9}) {
            latency.setPressure(FP16.name(), p);
            c.line("%-9.2f | %-34s | %-34s | %-34s", p,
                    describe(router.route(enterprise)), describe(router.route(consumer)), describe(router.route(api)));
        }
        c.line("");
        c.line("At 0.75 consumer is moved off the flagship on purpose, protecting headroom for enterprise;");
        c.line("at 0.90 even enterprise degrades, because the flagship can no longer meet its TTFT budget.");

        c.section("Act 2: flagship pool starts failing, circuit breaker trips");
        latency.setPressure(FP16.name(), 0.3);
        CircuitBreaker fp16 = breakers.get(FP16.name());
        for (int i = 0; i < 30; i++) {
            if (fp16.tryAcquire()) {
                if (i % 3 == 0) {
                    fp16.onSuccess();
                } else {
                    fp16.onFailure(); // e.g. CUDA OOM after a bad co-located workload
                }
            }
        }
        c.line("breaker[%s] = %s", FP16.name(), fp16.state());
        RoutingDecision d = router.route(enterprise);
        c.line("enterprise request -> %s", describe(d));
        if (d instanceof RoutingDecision.Routed r) {
            r.notes().forEach(n -> c.line("   skipped: %s", n));
        }
        c.line("Cost per token on the fallback: %.0f%% of flagship, quality %.2f vs %.2f.",
                INT8.relativeCost() * 100, INT8.qualityScore(), FP16.qualityScore());

        c.section("Act 3a: priority-aware load shedding at level 0.40");
        shedder.setLevel(0.4);
        for (Segment s : Segment.values()) {
            int admitted = 0;
            int n = 10_000;
            for (int i = 0; i < n; i++) {
                InferenceRequest req = new InferenceRequest(s.name().toLowerCase() + "-" + i, TenantId.of("t"), s,
                        500, 100, 5_000, true, "", 0.0);
                if (shedder.admit(req)) {
                    admitted++;
                }
            }
            c.line("%-10s configured shed %5.1f%%   admitted %5.1f%% of %,d", s,
                    shedder.sheddingFractionFor(s) * 100, 100.0 * admitted / n, n);
        }
        shedder.setLevel(0);

        c.section("Act 3b: prefix-cache-aware routing (8 replicas, 50 shared prefixes, Zipf traffic)");
        CacheSimResult affinity = simulate(true);
        CacheSimResult leastLoaded = simulate(false);
        c.line("%-26s hit rate %5.1f%%   peak in-flight on one replica %d", "least-loaded:",
                leastLoaded.hitRate * 100, leastLoaded.peakLoad);
        c.line("%-26s hit rate %5.1f%%   peak in-flight on one replica %d", "prefix-affinity (c=1.25):",
                affinity.hitRate * 100, affinity.peakLoad);

        c.takeaway("A smaller model is a better answer than a 503. Segment-aware degradation, priority shedding "
                + "and cache-aware routing turn a capacity crunch into a quality dip for the right users.");
    }

    private static String describe(RoutingDecision d) {
        return switch (d) {
            case RoutingDecision.Routed r -> String.format("%s%s ~%.0fms", r.variant().name(),
                    r.degraded() ? " (degraded)" : "", r.estimatedLatencyMs());
            case RoutingDecision.Rejected rj -> "REJECTED";
        };
    }

    private record CacheSimResult(double hitRate, int peakLoad) {
    }

    /**
     * Each replica holds an LRU of the 6 prefixes it most recently served (a stand-in for KV
     * blocks resident in VRAM). Requests stay in flight for 64 arrivals, which is what makes
     * load balance matter at all.
     */
    private static CacheSimResult simulate(boolean affinity) {
        List<String> replicas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            replicas.add("replica-" + i);
        }
        PrefixAffinityBalancer balancer = new PrefixAffinityBalancer(replicas, 1.25, 100);
        Map<String, LinkedHashMap<String, Boolean>> caches = new HashMap<>();
        Map<String, Integer> baselineLoad = new HashMap<>();
        for (String r : replicas) {
            caches.put(r, new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > 6;
                }
            });
            baselineLoad.put(r, 0);
        }
        double[] cdf = zipfCdf(50, 1.1);
        SplittableRandom rnd = new SplittableRandom(7);
        Deque<String> inFlight = new ArrayDeque<>();
        int hits = 0;
        int peak = 0;
        int n = 20_000;
        int rr = 0;
        for (int i = 0; i < n; i++) {
            String prefix = "prefix-" + sample(cdf, rnd.nextDouble());
            String chosen;
            if (affinity) {
                chosen = balancer.acquire(prefix);
                peak = Math.max(peak, balancer.loadOf(chosen));
            } else {
                // least-loaded with round-robin tie-breaking: the classic cache-oblivious default
                chosen = null;
                int best = Integer.MAX_VALUE;
                for (int k = 0; k < replicas.size(); k++) {
                    String r = replicas.get((rr + k) % replicas.size());
                    int l = baselineLoad.get(r);
                    if (l < best) {
                        best = l;
                        chosen = r;
                    }
                }
                rr++;
                baselineLoad.merge(chosen, 1, Integer::sum);
                peak = Math.max(peak, baselineLoad.get(chosen));
            }
            LinkedHashMap<String, Boolean> cache = caches.get(chosen);
            if (cache.containsKey(prefix)) {
                hits++;
            }
            cache.put(prefix, Boolean.TRUE);
            inFlight.addLast(chosen);
            if (inFlight.size() > 64) {
                String done = inFlight.removeFirst();
                if (affinity) {
                    balancer.release(done);
                } else {
                    baselineLoad.merge(done, -1, Integer::sum);
                }
            }
        }
        return new CacheSimResult((double) hits / n, peak);
    }

    private static double[] zipfCdf(int n, double s) {
        double[] cdf = new double[n];
        double sum = 0;
        for (int k = 1; k <= n; k++) {
            sum += 1.0 / Math.pow(k, s);
            cdf[k - 1] = sum;
        }
        for (int k = 0; k < n; k++) {
            cdf[k] /= sum;
        }
        return cdf;
    }

    private static int sample(double[] cdf, double u) {
        for (int i = 0; i < cdf.length; i++) {
            if (u <= cdf[i]) {
                return i;
            }
        }
        return cdf.length - 1;
    }
}
