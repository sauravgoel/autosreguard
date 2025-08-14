package io.autosreguard.detection;

import io.autosreguard.core.Preconditions;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Exponentially-weighted moving average/variance detector with a consecutive-sample requirement.
 *
 * <h2>How it works</h2>
 * Maintains an EWMA mean {@code mu} and variance {@code var}. A sample is "suspicious" when
 * {@code |x - mu| / sigma} exceeds {@code zThreshold} in the configured {@link Direction}.
 * An anomaly is only emitted after {@code consecutive} suspicious samples: single-sample
 * spikes are the main source of false positives, and false positives are what turns
 * autonomous remediation into a remediation loop.
 *
 * <h2>Baseline protection</h2>
 * Suspicious samples do not update the baseline. Otherwise a sustained incident slowly becomes
 * "normal" and the detector stops seeing it.
 *
 * <h2>Confidence</h2>
 * Confidence rises with z-score via a logistic curve centered at the threshold, and with the
 * number of consecutive samples. It feeds the remediation engine's autonomy tiers.
 */
public final class EwmaAnomalyDetector implements AnomalyDetector {

    /** Which deviations matter. Error rates only matter upward; throughput only downward. */
    public enum Direction { UP, DOWN, BOTH }

    private final String component;
    private final String signal;
    private final double alpha;
    private final double zThreshold;
    private final int consecutive;
    private final int warmupSamples;
    private final Direction direction;
    /** Floor on sigma so perfectly flat signals don't produce infinite z-scores on tiny changes. */
    private final double minSigma;

    private double mean;
    private double variance;
    private long samples;
    private int suspiciousRun;

    private EwmaAnomalyDetector(Builder b) {
        this.component = Objects.requireNonNull(b.component, "component");
        this.signal = Objects.requireNonNull(b.signal, "signal");
        this.alpha = Preconditions.checkFraction(b.alpha, "alpha");
        this.zThreshold = Preconditions.checkPositive(b.zThreshold, "zThreshold");
        this.consecutive = Preconditions.checkPositive(b.consecutive, "consecutive");
        this.warmupSamples = Preconditions.checkPositive(b.warmupSamples, "warmupSamples");
        this.direction = Objects.requireNonNull(b.direction, "direction");
        this.minSigma = Preconditions.checkPositive(b.minSigma, "minSigma");
    }

    public static Builder builder(String component, String signal) {
        return new Builder(component, signal);
    }

    @Override
    public synchronized Optional<Anomaly> observe(Instant at, double x) {
        if (Double.isNaN(x)) {
            return Optional.empty(); // missing data is a separate alert, not an anomaly on this signal
        }
        if (samples < warmupSamples) {
            learn(x);
            return Optional.empty();
        }
        double sigma = Math.max(Math.sqrt(variance), minSigma);
        double z = (x - mean) / sigma;
        boolean suspicious = switch (direction) {
            case UP -> z > zThreshold;
            case DOWN -> z < -zThreshold;
            case BOTH -> Math.abs(z) > zThreshold;
        };
        if (!suspicious) {
            suspiciousRun = 0;
            learn(x);
            return Optional.empty();
        }
        suspiciousRun++;
        if (suspiciousRun < consecutive) {
            return Optional.empty();
        }
        return Optional.of(new Anomaly(component, signal, x, mean, z, confidence(z), at));
    }

    private void learn(double x) {
        if (samples == 0) {
            mean = x;
            variance = 0;
        } else {
            double diff = x - mean;
            double incr = alpha * diff;
            mean += incr;
            // West's incremental EWMA variance.
            variance = (1 - alpha) * (variance + diff * incr);
        }
        samples++;
    }

    private double confidence(double z) {
        double excess = Math.abs(z) - zThreshold;
        double zPart = 1.0 / (1.0 + Math.exp(-excess));          // 0.5 at threshold, -> 1 far beyond
        double persistence = 1.0 - Math.pow(0.5, suspiciousRun);   // 0.5, 0.75, 0.875, ...
        return Math.min(1.0, zPart * 0.6 + persistence * 0.4);
    }

    @Override
    public synchronized double baseline() {
        return mean;
    }

    public String component() {
        return component;
    }

    public String signal() {
        return signal;
    }

    /** Builder with defaults tuned for 10-60s samples of rates and latencies. */
    public static final class Builder {
        private final String component;
        private final String signal;
        private double alpha = 0.05;
        private double zThreshold = 4.0;
        private int consecutive = 3;
        private int warmupSamples = 30;
        private Direction direction = Direction.UP;
        private double minSigma = 1e-6;

        private Builder(String component, String signal) {
            this.component = component;
            this.signal = signal;
        }

        public Builder alpha(double a) { this.alpha = a; return this; }
        public Builder zThreshold(double z) { this.zThreshold = z; return this; }
        public Builder consecutive(int n) { this.consecutive = n; return this; }
        public Builder warmupSamples(int n) { this.warmupSamples = n; return this; }
        public Builder direction(Direction d) { this.direction = d; return this; }
        public Builder minSigma(double s) { this.minSigma = s; return this; }

        public EwmaAnomalyDetector build() {
            return new EwmaAnomalyDetector(this);
        }
    }
}
