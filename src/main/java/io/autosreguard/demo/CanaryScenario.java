package io.autosreguard.demo;

import io.autosreguard.canary.CanaryAnalyzer;
import io.autosreguard.canary.CanarySample;
import io.autosreguard.canary.CanaryVerdict;

import java.util.SplittableRandom;

/**
 * Model canaries must roll back automatically on output-quality regression, not only on
 * latency or error rate.
 *
 * <p>The candidate model is a new fine-tune that is faster and just as reliable, but
 * measurably worse at the task. A latency/error-only canary would promote it; the quality
 * gate catches it.
 */
final class CanaryScenario implements Scenario {

    @Override
    public String name() {
        return "canary";
    }

    @Override
    public void run(Console c) {
        c.banner("Scenario 6: The canary that looked perfect",
                "for models, output quality is a first-class rollout signal");

        CanarySample baseline = new CanarySample();
        CanarySample canary = new CanarySample();
        CanaryAnalyzer full = CanaryAnalyzer.defaults();
        // Same thresholds, but the quality check can never fail: what most pipelines run today.
        CanaryAnalyzer opsOnly = new CanaryAnalyzer(1000, 200, 2.33, 0.002, 1.2, 1.0);

        SplittableRandom rnd = new SplittableRandom(2026);
        c.section("Rollout: baseline llama-70b-v42 vs canary llama-70b-v43 (500 req/arm/batch, 20% scored)");
        c.line("canary profile: 10%% faster, same 0.1%% error rate, quality 0.79 vs 0.82 (sd 0.10)");
        for (int batch = 1; batch <= 4; batch++) {
            feed(baseline, rnd, 500, 900, 0.82);
            feed(canary, rnd, 500, 810, 0.79);
            CanaryVerdict v = full.analyze(baseline, canary);
            CanaryVerdict naive = opsOnly.analyze(baseline, canary);
            c.section(String.format("Batch %d  (n=%d/arm)", batch, baseline.requests()));
            v.reasons().forEach(r -> c.line("  %s", r));
            c.line("quality-aware verdict: %-8s   ops-only verdict: %s", v.decision(), naive.decision());
            if (v.decision() == CanaryVerdict.Decision.ROLLBACK) {
                c.line("-> automatic rollback of llama-70b-v43; exposure limited to %,d canary requests",
                        canary.requests());
                break;
            }
        }

        c.takeaway("Error rate and latency say nothing about whether answers got worse. Score a sample of "
                + "outputs per arm (eval model, heuristics, user feedback) and gate promotion on it.");
    }

    /** Simulates one batch: 0.1% errors, log-normal-ish latency, 20% of successes quality-scored. */
    private static void feed(CanarySample s, SplittableRandom rnd, int n, double medianMs, double quality) {
        for (int i = 0; i < n; i++) {
            boolean error = rnd.nextDouble() < 0.001;
            double latency = medianMs * Math.exp(0.25 * gaussian(rnd));
            s.recordRequest(error, latency);
            if (!error && rnd.nextDouble() < 0.2) {
                s.recordQuality(Math.max(0, Math.min(1, quality + 0.10 * gaussian(rnd))));
            }
        }
    }

    /** Box-Muller; SplittableRandom has no nextGaussian on all JDK levels we target. */
    static double gaussian(SplittableRandom rnd) {
        double u1 = Math.max(1e-12, rnd.nextDouble());
        double u2 = rnd.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }
}
