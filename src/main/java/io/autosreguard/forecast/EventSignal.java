package io.autosreguard.forecast;

import io.autosreguard.core.Preconditions;

import java.time.Instant;
import java.util.Objects;

/**
 * A known upcoming event expected to change demand: a product launch, a marketing campaign,
 * a major sports final, a feature rollout to a new region.
 *
 * <p>Historical time series cannot predict a launch that has never happened. Event signals
 * inject that knowledge explicitly, so capacity is in place <em>before</em> the wave arrives.
 *
 * @param name       human-readable label (shows up in scaling decision explanations)
 * @param start      when the effect begins
 * @param end        when the effect ends
 * @param multiplier demand multiplier during the event, e.g. {@code 2.5}
 */
public record EventSignal(String name, Instant start, Instant end, double multiplier) {

    public EventSignal {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("event end must be after start");
        }
        Preconditions.checkPositive(multiplier, "multiplier");
    }

    public boolean activeAt(Instant t) {
        return !t.isBefore(start) && t.isBefore(end);
    }
}
