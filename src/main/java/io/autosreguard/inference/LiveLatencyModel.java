package io.autosreguard.inference;

import io.autosreguard.core.Preconditions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learns how actual latency deviates from each variant's static profile, plus current pool
 * pressure. Routing on static thresholds fails the moment reality diverges from the load test
 * (thermal throttling, a noisy neighbour, a hot prefix); routing on a live model adapts.
 *
 * <p>Tracks, per variant, an EWMA of {@code observed / expected} for TTFT and TPOT, and a
 * pressure value in [0, +inf) where 1.0 means the pool is at its sustainable limit.
 */
public final class LiveLatencyModel {

    private record State(double ttftFactor, double tpotFactor, double pressure) {
    }

    private final double alpha;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public LiveLatencyModel(double alpha) {
        this.alpha = Preconditions.checkFraction(alpha, "alpha");
    }

    /** Records one completed request on {@code v}. */
    public void observe(ModelVariant v, int promptTokens, double observedTtftMs, double observedTpotMs) {
        double expectedTtft = staticTtft(v, promptTokens);
        states.compute(v.name(), (k, s) -> {
            State cur = s == null ? new State(1, 1, 0) : s;
            double tf = observedTtftMs > 0 ? ewma(cur.ttftFactor(), observedTtftMs / expectedTtft) : cur.ttftFactor();
            double pf = observedTpotMs > 0 ? ewma(cur.tpotFactor(), observedTpotMs / v.tpotMs()) : cur.tpotFactor();
            return new State(tf, pf, cur.pressure());
        });
    }

    /** Sets current pressure for a variant's pool, e.g. queue depth / sustainable depth. */
    public void setPressure(String variant, double pressure) {
        Preconditions.checkNonNegative(pressure, "pressure");
        states.compute(variant, (k, s) -> s == null ? new State(1, 1, pressure)
                : new State(s.ttftFactor(), s.tpotFactor(), pressure));
    }

    public double pressure(String variant) {
        State s = states.get(variant);
        return s == null ? 0 : s.pressure();
    }

    /**
     * Estimated TTFT under current conditions. Queueing delay is approximated with the M/M/1
     * inflation factor {@code 1/(1-rho)}, capped at rho=0.95 to keep estimates finite.
     */
    public double estimateTtftMs(ModelVariant v, int promptTokens) {
        State s = states.getOrDefault(v.name(), new State(1, 1, 0));
        double rho = Math.min(0.95, s.pressure());
        return staticTtft(v, promptTokens) * s.ttftFactor() / (1 - rho);
    }

    public double estimateTpotMs(ModelVariant v) {
        State s = states.getOrDefault(v.name(), new State(1, 1, 0));
        return v.tpotMs() * s.tpotFactor();
    }

    private static double staticTtft(ModelVariant v, int promptTokens) {
        return v.baseTtftMs() + v.prefillMsPerKTok() * promptTokens / 1000.0;
    }

    private double ewma(double prev, double sample) {
        return (1 - alpha) * prev + alpha * sample;
    }
}
