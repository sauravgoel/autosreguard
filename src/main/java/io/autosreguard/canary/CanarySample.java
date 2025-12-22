package io.autosreguard.canary;

import io.autosreguard.core.Preconditions;

import java.util.Arrays;

/**
 * Accumulated metrics for one arm (baseline or canary) of a model rollout.
 *
 * <p>Quality is the metric that makes model rollouts different from binary rollouts. A new
 * model can have perfect error rates and better latency while giving worse answers. Quality
 * here is any per-request score in [0,1]: an online eval, a judge model, task success, or a
 * user-feedback proxy.
 *
 * <p>Latencies are kept in a bounded reservoir (uniform sampling) for percentile estimates.
 */
public final class CanarySample {

    private static final int RESERVOIR = 10_000;

    private long requests;
    private long errors;
    private long qualityN;
    private double qualitySum;
    private double qualitySumSq;
    private final double[] latencies = new double[RESERVOIR];
    private int latencyCount;
    private long latencySeen;
    private final java.util.SplittableRandom rnd = new java.util.SplittableRandom(42);

    public synchronized void recordRequest(boolean error, double latencyMs) {
        requests++;
        if (error) {
            errors++;
            return;
        }
        Preconditions.checkNonNegative(latencyMs, "latencyMs");
        latencySeen++;
        if (latencyCount < RESERVOIR) {
            latencies[latencyCount++] = latencyMs;
        } else {
            long j = rnd.nextLong(latencySeen);
            if (j < RESERVOIR) {
                latencies[(int) j] = latencyMs;
            }
        }
    }

    public synchronized void recordQuality(double score) {
        Preconditions.checkUnitInterval(score, "score");
        qualityN++;
        qualitySum += score;
        qualitySumSq += score * score;
    }

    public synchronized long requests() { return requests; }
    public synchronized long errors() { return errors; }
    public synchronized long qualityCount() { return qualityN; }

    public synchronized double errorRate() {
        return requests == 0 ? 0 : (double) errors / requests;
    }

    public synchronized double qualityMean() {
        return qualityN == 0 ? Double.NaN : qualitySum / qualityN;
    }

    public synchronized double qualityVariance() {
        if (qualityN < 2) {
            return Double.NaN;
        }
        double mean = qualitySum / qualityN;
        return Math.max(0, (qualitySumSq - qualityN * mean * mean) / (qualityN - 1));
    }

    /** Percentile from the reservoir using nearest-rank; NaN if no data. */
    public synchronized double latencyPercentile(double p) {
        if (latencyCount == 0) {
            return Double.NaN;
        }
        double[] copy = Arrays.copyOf(latencies, latencyCount);
        Arrays.sort(copy);
        int idx = (int) Math.ceil(p * copy.length) - 1;
        return copy[Math.max(0, Math.min(copy.length - 1, idx))];
    }
}
