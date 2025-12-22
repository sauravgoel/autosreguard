package io.autosreguard.canary;

import io.autosreguard.core.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Statistical comparison of a canary model version against its baseline.
 *
 * <p>Three independent checks; any failure means ROLLBACK:
 * <ol>
 *   <li><b>Errors</b>: one-sided two-proportion z-test. Rolls back if the canary error rate is
 *       significantly higher (z > {@code zCritical}) AND the absolute increase exceeds
 *       {@code maxErrorRateIncrease}: statistical and practical significance.</li>
 *   <li><b>Latency</b>: p95 ratio canary/baseline must be at most {@code maxLatencyRatio}.</li>
 *   <li><b>Quality</b>: one-sided Welch's t-statistic on per-request quality scores. Rolls back
 *       if quality dropped significantly AND by more than {@code maxQualityDrop}. This is the
 *       check that catches a model that is fast, error-free, and wrong.</li>
 * </ol>
 * Verdicts need {@code minRequests} and {@code minQualitySamples} per arm; below that we
 * CONTINUE, since promoting on thin evidence is how bad models ship.
 */
public final class CanaryAnalyzer {

    private final long minRequests;
    private final long minQualitySamples;
    private final double zCritical;
    private final double maxErrorRateIncrease;
    private final double maxLatencyRatio;
    private final double maxQualityDrop;

    public CanaryAnalyzer(long minRequests, long minQualitySamples, double zCritical,
                          double maxErrorRateIncrease, double maxLatencyRatio, double maxQualityDrop) {
        this.minRequests = Preconditions.checkPositive(minRequests, "minRequests");
        this.minQualitySamples = Preconditions.checkPositive(minQualitySamples, "minQualitySamples");
        this.zCritical = Preconditions.checkPositive(zCritical, "zCritical");
        this.maxErrorRateIncrease = Preconditions.checkNonNegative(maxErrorRateIncrease, "maxErrorRateIncrease");
        this.maxLatencyRatio = Preconditions.checkPositive(maxLatencyRatio, "maxLatencyRatio");
        this.maxQualityDrop = Preconditions.checkNonNegative(maxQualityDrop, "maxQualityDrop");
    }

    /** z = 2.33 is ~99% one-sided. */
    public static CanaryAnalyzer defaults() {
        return new CanaryAnalyzer(1000, 200, 2.33, 0.002, 1.2, 0.02);
    }

    public CanaryVerdict analyze(CanarySample baseline, CanarySample canary) {
        List<String> reasons = new ArrayList<>();
        boolean fail = false;
        boolean enough = true;

        // --- 1. Errors -------------------------------------------------------------
        if (baseline.requests() < minRequests || canary.requests() < minRequests) {
            enough = false;
            reasons.add("errors: need " + minRequests + " requests per arm (have "
                    + baseline.requests() + "/" + canary.requests() + ")");
        } else {
            double z = twoProportionZ(baseline.errors(), baseline.requests(), canary.errors(), canary.requests());
            double delta = canary.errorRate() - baseline.errorRate();
            boolean bad = z > zCritical && delta > maxErrorRateIncrease;
            fail |= bad;
            reasons.add(String.format(Locale.ROOT, "errors: base=%.4f canary=%.4f z=%.2f -> %s",
                    baseline.errorRate(), canary.errorRate(), z, bad ? "FAIL" : "ok"));
        }

        // --- 2. Latency ------------------------------------------------------------
        double bp95 = baseline.latencyPercentile(0.95);
        double cp95 = canary.latencyPercentile(0.95);
        if (Double.isNaN(bp95) || Double.isNaN(cp95)) {
            enough = false;
            reasons.add("latency: no successful requests yet");
        } else {
            double ratio = cp95 / bp95;
            boolean bad = enough && ratio > maxLatencyRatio;
            fail |= bad;
            reasons.add(String.format(Locale.ROOT, "latency p95: base=%.0fms canary=%.0fms ratio=%.2f -> %s",
                    bp95, cp95, ratio, bad ? "FAIL" : "ok"));
        }

        // --- 3. Quality ------------------------------------------------------------
        if (baseline.qualityCount() < minQualitySamples || canary.qualityCount() < minQualitySamples) {
            enough = false;
            reasons.add("quality: need " + minQualitySamples + " scored samples per arm (have "
                    + baseline.qualityCount() + "/" + canary.qualityCount() + ")");
        } else {
            double drop = baseline.qualityMean() - canary.qualityMean();
            double se = Math.sqrt(baseline.qualityVariance() / baseline.qualityCount()
                    + canary.qualityVariance() / canary.qualityCount());
            double t = se == 0 ? (drop > 0 ? Double.POSITIVE_INFINITY : 0) : drop / se;
            boolean bad = t > zCritical && drop > maxQualityDrop;
            fail |= bad;
            reasons.add(String.format(Locale.ROOT, "quality: base=%.3f canary=%.3f drop=%.3f t=%.2f -> %s",
                    baseline.qualityMean(), canary.qualityMean(), drop, t, bad ? "FAIL" : "ok"));
        }

        // A clear failure rolls back even before every arm has full data: fail fast on bad news.
        if (fail) {
            return new CanaryVerdict(CanaryVerdict.Decision.ROLLBACK, reasons);
        }
        return new CanaryVerdict(enough ? CanaryVerdict.Decision.PROMOTE : CanaryVerdict.Decision.CONTINUE, reasons);
    }

    /** One-sided pooled two-proportion z statistic; positive when p2 > p1. */
    static double twoProportionZ(long x1, long n1, long x2, long n2) {
        double p1 = (double) x1 / n1;
        double p2 = (double) x2 / n2;
        double p = (double) (x1 + x2) / (n1 + n2);
        double se = Math.sqrt(p * (1 - p) * (1.0 / n1 + 1.0 / n2));
        if (se == 0) {
            return 0;
        }
        return (p2 - p1) / se;
    }
}
