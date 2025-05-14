package io.autosreguard.forecast;

import io.autosreguard.core.Preconditions;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Additive Holt-Winters (triple exponential smoothing) forecaster.
 *
 * <h2>Why this model?</h2>
 * Inference demand is dominated by three components: a level, a trend
 * (growth), and strong seasonality (geographic daylight cycles, weekday rhythm). Holt-Winters
 * models exactly those three, is O(1) per update, has no dependencies, and is explainable,
 * which matters when a forecast drives an autonomous scaling decision someone will audit.
 * Heavier ML models can implement {@link DemandForecaster} and be compared in shadow mode.
 *
 * <h2>Equations</h2>
 * For observation {@code y} at step {@code t}, season length {@code m}:
 * <pre>
 *   level    L = alpha * (y - S[t mod m]) + (1 - alpha) * (L + T)
 *   trend    T = beta  * (L - Lprev)      + (1 - beta)  * T
 *   season   S[t mod m] = gamma * (y - L) + (1 - gamma) * S[t mod m]
 *   forecast y(t+h) = L + h*T + S[(t+h) mod m]
 * </pre>
 *
 * <h2>Uncertainty</h2>
 * We track an exponentially weighted variance of one-step-ahead residuals and widen the
 * interval with {@code sqrt(h)}, a standard random-walk approximation. It's deliberately
 * simple; the planner only needs a sane, conservative upper bound.
 *
 * <h2>Gaps</h2>
 * Missing samples are imputed with the model's own one-step forecast so that the seasonal
 * index stays aligned with wall-clock time. A scraper outage should not shift your
 * "9am peak" to 9:05.
 */
public final class HoltWintersForecaster implements DemandForecaster {

    /** z-score for an approximately 95% two-sided interval. */
    private static final double Z_95 = 1.96;

    private final double alpha;
    private final double beta;
    private final double gamma;
    private final int seasonLength;
    private final Duration step;

    private final double[] seasonal;
    private final double[] warmup;
    private int warmupCount;

    private double level;
    private double trend;
    private double residualVariance;
    private long t; // steps observed since initialization
    private Instant lastAt;
    private boolean initialized;

    /**
     * @param alpha        level smoothing in (0,1); higher reacts faster
     * @param beta         trend smoothing in (0,1); keep small to avoid chasing noise
     * @param gamma        seasonal smoothing in (0,1)
     * @param seasonLength steps per season, e.g. 288 for a daily season at 5-minute steps
     * @param step         sampling interval
     */
    public HoltWintersForecaster(double alpha, double beta, double gamma, int seasonLength, Duration step) {
        this.alpha = Preconditions.checkFraction(alpha, "alpha");
        this.beta = Preconditions.checkFraction(beta, "beta");
        this.gamma = Preconditions.checkFraction(gamma, "gamma");
        this.seasonLength = Preconditions.checkPositive(seasonLength, "seasonLength");
        this.step = Objects.requireNonNull(step, "step");
        Preconditions.checkPositive(step.toMillis(), "step");
        this.seasonal = new double[seasonLength];
        this.warmup = new double[seasonLength * 2];
    }

    /** Reasonable defaults for a daily season sampled every {@code step}. */
    public static HoltWintersForecaster daily(Duration step) {
        int m = (int) (Duration.ofDays(1).toMillis() / step.toMillis());
        return new HoltWintersForecaster(0.3, 0.02, 0.2, m, step);
    }

    @Override
    public void observe(Instant at, double value) {
        Objects.requireNonNull(at, "at");
        Preconditions.checkNonNegative(value, "value");
        if (lastAt != null && !at.isAfter(lastAt)) {
            throw new IllegalArgumentException("observations must be strictly increasing in time: "
                    + at + " <= " + lastAt);
        }
        if (!initialized) {
            warmupObserve(at, value);
            return;
        }
        // Impute any missing steps so the seasonal index stays aligned with wall-clock time.
        long missing = missingStepsBefore(at);
        for (long i = 0; i < missing; i++) {
            update(oneStepAhead(), false);
        }
        update(value, true);
        lastAt = at;
    }

    private long missingStepsBefore(Instant at) {
        long elapsed = Duration.between(lastAt, at).toMillis();
        long steps = Math.round((double) elapsed / step.toMillis());
        // Cap imputation: after a very long gap, a full season of made-up data is already too much.
        return Math.min(Math.max(0, steps - 1), seasonLength);
    }

    private void warmupObserve(Instant at, double value) {
        warmup[warmupCount++] = value;
        lastAt = at;
        if (warmupCount == warmup.length) {
            initializeFromTwoSeasons();
        }
    }

    /**
     * Classical initialization from two full seasons: level is the first season's mean,
     * trend is the average per-step change between the season means, and seasonal indices are
     * the average deviation from each season's mean.
     */
    private void initializeFromTwoSeasons() {
        int m = seasonLength;
        double mean1 = 0;
        double mean2 = 0;
        for (int i = 0; i < m; i++) {
            mean1 += warmup[i];
            mean2 += warmup[m + i];
        }
        mean1 /= m;
        mean2 /= m;
        trend = (mean2 - mean1) / m;
        for (int i = 0; i < m; i++) {
            seasonal[i] = ((warmup[i] - mean1) + (warmup[m + i] - mean2)) / 2.0;
        }
        level = mean2;
        t = 2L * m;
        // Seed residual variance from the in-sample fit so early intervals aren't absurdly tight.
        double ss = 0;
        for (int i = 0; i < m; i++) {
            double fitted = mean2 + seasonal[i];
            double r = warmup[m + i] - fitted;
            ss += r * r;
        }
        residualVariance = ss / m;
        initialized = true;
    }

    private double oneStepAhead() {
        return Math.max(0, level + trend + seasonal[(int) (t % seasonLength)]);
    }

    private void update(double y, boolean updateResidual) {
        int idx = (int) (t % seasonLength);
        if (updateResidual) {
            double residual = y - (level + trend + seasonal[idx]);
            // EWMA variance with the same responsiveness as the level.
            residualVariance = (1 - alpha) * residualVariance + alpha * residual * residual;
        }
        double prevLevel = level;
        level = alpha * (y - seasonal[idx]) + (1 - alpha) * (level + trend);
        trend = beta * (level - prevLevel) + (1 - beta) * trend;
        seasonal[idx] = gamma * (y - level) + (1 - gamma) * seasonal[idx];
        t++;
    }

    @Override
    public Forecast forecast(Instant target) {
        if (!initialized) {
            throw new IllegalStateException("forecaster is warming up (" + warmupCount + "/" + warmup.length
                    + " samples); check isReady() first");
        }
        long h = Math.max(1, Math.round((double) Duration.between(lastAt, target).toMillis() / step.toMillis()));
        // t is the index of the NEXT step, so h steps ahead is season index (t + h - 1).
        double point = level + h * trend + seasonal[(int) ((t + h - 1) % seasonLength)];
        point = Math.max(0, point);
        double halfWidth = Z_95 * Math.sqrt(residualVariance) * Math.sqrt(h);
        return new Forecast(target, point, Math.max(0, point - halfWidth), point + halfWidth);
    }

    @Override
    public boolean isReady() {
        return initialized;
    }

    public Duration step() {
        return step;
    }
}
