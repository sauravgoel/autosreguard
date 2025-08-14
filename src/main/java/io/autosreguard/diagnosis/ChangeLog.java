package io.autosreguard.diagnosis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thread-safe, time-ordered, bounded log of production changes.
 *
 * <p>In a real deployment this is fed by CI/CD webhooks and config-management audit streams.
 */
public final class ChangeLog {

    private final List<ChangeEvent> events = new ArrayList<>();
    private final int maxEvents;

    public ChangeLog(int maxEvents) {
        if (maxEvents <= 0) {
            throw new IllegalArgumentException("maxEvents must be positive");
        }
        this.maxEvents = maxEvents;
    }

    public synchronized void record(ChangeEvent e) {
        // Keep sorted by time; out-of-order webhook delivery is normal.
        int i = events.size();
        while (i > 0 && events.get(i - 1).at().isAfter(e.at())) {
            i--;
        }
        events.add(i, e);
        if (events.size() > maxEvents) {
            events.remove(0);
        }
    }

    /** Changes in {@code [end - window, end]}, oldest first. */
    public synchronized List<ChangeEvent> between(Instant end, Duration window) {
        Instant start = end.minus(window);
        List<ChangeEvent> out = new ArrayList<>();
        for (ChangeEvent e : events) {
            if (!e.at().isBefore(start) && !e.at().isAfter(end)) {
                out.add(e);
            }
        }
        return out;
    }

    /** The most recent change to a component, if any. */
    public synchronized Optional<ChangeEvent> latestFor(String component) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).component().equals(component)) {
                return Optional.of(events.get(i));
            }
        }
        return Optional.empty();
    }
}
