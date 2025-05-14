package io.autosreguard.forecast;

import java.time.Instant;

/**
 * Predicts future demand (for example, tokens/second or requests/second) from history.
 *
 * <p>Implementations must be safe to call from a single scheduler thread; they are not
 * required to be thread-safe. The capacity loop is naturally single-threaded.
 */
public interface DemandForecaster {

    /** Feeds an observation taken at {@code at}. Observations must be in time order. */
    void observe(Instant at, double value);

    /** Predicts demand at {@code target}, which must be after the last observation. */
    Forecast forecast(Instant target);

    /**
     * Whether the model has seen enough history to produce meaningful forecasts. Callers
     * should fall back to reactive scaling until this returns {@code true}.
     */
    boolean isReady();
}
