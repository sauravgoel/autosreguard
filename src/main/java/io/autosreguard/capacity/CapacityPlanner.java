package io.autosreguard.capacity;

import io.autosreguard.core.Preconditions;
import io.autosreguard.forecast.DemandForecaster;
import io.autosreguard.forecast.EventAwareForecaster;
import io.autosreguard.forecast.Forecast;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Turns demand forecasts into replica counts, provisioning ahead of demand.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Look ahead over {@code [now, now + warmup + lookahead]} in forecaster steps.</li>
 *   <li>Take the maximum <em>upper bound</em> of the forecast over that window.</li>
 *   <li>Required replicas = {@code ceil(peak / (tokensPerSec * targetUtilization))}.</li>
 *   <li>Scale up immediately (cheap to undo). Scale down cautiously: at most
 *       {@code maxScaleDownFraction} per cycle, and never below what the forecast needs
 *       during the warm-up window, because scaling back up would pay warm-up again.</li>
 * </ol>
 *
 * <h2>Asymmetry is intentional</h2>
 * Over-provisioning wastes money for minutes. Under-provisioning degrades millions of requests.
 * The planner is biased accordingly.
 */
public final class CapacityPlanner {

    private final DemandForecaster forecaster;
    private final GpuProfile profile;
    private final Clock clock;
    private final Duration lookahead;
    private final Duration sampleStep;
    private final double targetUtilization;
    private final int minReplicas;
    private final int maxReplicas;
    private final double maxScaleDownFraction;

    private CapacityPlanner(Builder b) {
        this.forecaster = Objects.requireNonNull(b.forecaster, "forecaster");
        this.profile = Objects.requireNonNull(b.profile, "profile");
        this.clock = Objects.requireNonNull(b.clock, "clock");
        this.lookahead = Objects.requireNonNull(b.lookahead, "lookahead");
        this.sampleStep = Objects.requireNonNull(b.sampleStep, "sampleStep");
        this.targetUtilization = Preconditions.checkFraction(b.targetUtilization, "targetUtilization");
        this.minReplicas = Preconditions.checkPositive(b.minReplicas, "minReplicas");
        this.maxReplicas = Preconditions.checkPositive(b.maxReplicas, "maxReplicas");
        this.maxScaleDownFraction = Preconditions.checkFraction(b.maxScaleDownFraction, "maxScaleDownFraction");
        if (minReplicas > maxReplicas) {
            throw new IllegalArgumentException("minReplicas > maxReplicas");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Runs one planning cycle.
     *
     * @param currentReplicas replicas currently serving
     * @param currentDemand   observed demand right now (tokens/sec), used by the reactive fallback
     */
    public ScalingDecision plan(int currentReplicas, double currentDemand) {
        Instant now = clock.instant();
        if (!forecaster.isReady()) {
            // Reactive fallback: size for what we see now. Honest about being worse.
            int needed = replicasFor(currentDemand);
            int desired = clamp(Math.max(needed, scaleDownFloor(currentReplicas)));
            return new ScalingDecision(currentReplicas, desired, currentDemand, ScalingDecision.Mode.REACTIVE,
                    List.of(), "forecaster warming up; reactive sizing for current demand "
                    + fmt(currentDemand) + " tok/s");
        }

        Instant horizonEnd = now.plus(profile.warmup()).plus(lookahead);
        Instant warmupEnd = now.plus(profile.warmup());
        double peak = 0;
        double peakDuringWarmup = 0;
        Instant peakAt = now;
        for (Instant t = now.plus(sampleStep); !t.isAfter(horizonEnd); t = t.plus(sampleStep)) {
            Forecast f = forecaster.forecast(t);
            if (f.upper() > peak) {
                peak = f.upper();
                peakAt = t;
            }
            if (!t.isAfter(warmupEnd)) {
                peakDuringWarmup = Math.max(peakDuringWarmup, f.upper());
            }
        }
        // Never plan below what is actually happening right now; a forecast miss must not shed load.
        peak = Math.max(peak, currentDemand);

        int required = replicasFor(peak);
        List<String> events = forecaster instanceof EventAwareForecaster ea
                ? ea.activeEventNames(peakAt) : List.of();

        int desired;
        String reason;
        if (required >= currentReplicas) {
            desired = clamp(required);
            reason = String.format(Locale.ROOT,
                    "forecast peak %s tok/s at %s (within warmup %ds + lookahead %ds) needs %d replicas @%.0f%% util",
                    fmt(peak), peakAt, profile.warmup().toSeconds(), lookahead.toSeconds(), required,
                    targetUtilization * 100);
        } else {
            // Scale down gradually, and keep enough for the warm-up window so we do not flap.
            int warmupNeed = replicasFor(Math.max(peakDuringWarmup, currentDemand));
            desired = clamp(Math.max(Math.max(required, warmupNeed), scaleDownFloor(currentReplicas)));
            reason = String.format(Locale.ROOT,
                    "demand easing: forecast peak %s tok/s needs %d; stepping down gradually (max %.0f%%/cycle)",
                    fmt(peak), required, maxScaleDownFraction * 100);
        }
        if (!events.isEmpty()) {
            reason += "; events=" + events;
        }
        return new ScalingDecision(currentReplicas, desired, peak, ScalingDecision.Mode.PREDICTIVE, events, reason);
    }

    int replicasFor(double tokensPerSec) {
        double perReplica = profile.sustainableTokensPerSec() * targetUtilization;
        return (int) Math.ceil(tokensPerSec / perReplica);
    }

    private int scaleDownFloor(int current) {
        return (int) Math.ceil(current * (1.0 - maxScaleDownFraction));
    }

    private int clamp(int n) {
        return Math.max(minReplicas, Math.min(maxReplicas, n));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    /** Fluent builder; planners have too many knobs for a readable constructor. */
    public static final class Builder {
        private DemandForecaster forecaster;
        private GpuProfile profile;
        private Clock clock;
        private Duration lookahead = Duration.ofMinutes(15);
        private Duration sampleStep = Duration.ofMinutes(1);
        private double targetUtilization = 0.7;
        private int minReplicas = 2;
        private int maxReplicas = 1000;
        private double maxScaleDownFraction = 0.1;

        public Builder forecaster(DemandForecaster f) { this.forecaster = f; return this; }
        public Builder profile(GpuProfile p) { this.profile = p; return this; }
        public Builder clock(Clock c) { this.clock = c; return this; }
        /** How far past warm-up to look. */
        public Builder lookahead(Duration d) { this.lookahead = d; return this; }
        /** Resolution at which the forecast is sampled; match the forecaster's step. */
        public Builder sampleStep(Duration d) { this.sampleStep = d; return this; }
        /** Planned utilization per replica; the gap to 1.0 is burst headroom. */
        public Builder targetUtilization(double u) { this.targetUtilization = u; return this; }
        /** Floor for redundancy: never fewer than 2 in production (N+1). */
        public Builder minReplicas(int n) { this.minReplicas = n; return this; }
        public Builder maxReplicas(int n) { this.maxReplicas = n; return this; }
        public Builder maxScaleDownFraction(double f) { this.maxScaleDownFraction = f; return this; }

        public CapacityPlanner build() {
            return new CapacityPlanner(this);
        }
    }
}
