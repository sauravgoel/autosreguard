package io.autosreguard.forecast;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Decorates a base forecaster with scheduled {@link EventSignal}s.
 *
 * <p>Two subtleties handled here:
 * <ul>
 *   <li><b>Overlapping events</b> compound multiplicatively (a launch during a sports final).</li>
 *   <li><b>Training contamination</b>: while an event is active, observations are divided by the
 *       event multiplier before reaching the base model. Otherwise the model "learns" that every
 *       Tuesday at 10:00 has a product launch and over-provisions next week.</li>
 * </ul>
 */
public final class EventAwareForecaster implements DemandForecaster {

    private final DemandForecaster base;
    private final List<EventSignal> events = new CopyOnWriteArrayList<>();

    public EventAwareForecaster(DemandForecaster base) {
        this.base = Objects.requireNonNull(base, "base");
    }

    /** Registers an event. Safe to call from any thread (e.g. a campaign-calendar webhook). */
    public void schedule(EventSignal event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    /** Removes events that ended before {@code now}. Call periodically. */
    public void pruneBefore(Instant now) {
        events.removeIf(e -> !e.end().isAfter(now));
    }

    public double multiplierAt(Instant t) {
        double m = 1.0;
        for (EventSignal e : events) {
            if (e.activeAt(t)) {
                m *= e.multiplier();
            }
        }
        return m;
    }

    /** Names of events active at {@code t}, for explainable scaling decisions. */
    public List<String> activeEventNames(Instant t) {
        return events.stream().filter(e -> e.activeAt(t)).map(EventSignal::name).toList();
    }

    @Override
    public void observe(Instant at, double value) {
        // De-seasonalize the event out of the training signal (see class docs).
        base.observe(at, value / multiplierAt(at));
    }

    @Override
    public Forecast forecast(Instant target) {
        return base.forecast(target).scaled(multiplierAt(target));
    }

    @Override
    public boolean isReady() {
        return base.isReady();
    }
}
