package io.autosreguard.remediation;

import io.autosreguard.core.Preconditions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;

/**
 * Prevents "remediation loops": automated actions that oscillate a system into instability.
 *
 * <p>Three independent guardrails, any of which blocks an action and escalates to a human:
 * <ol>
 *   <li><b>Per-target cooldown</b>: no two automated actions on the same target within
 *       {@code cooldown}. Gives the previous action time to take effect before judging it.</li>
 *   <li><b>Flap detection</b>: if the inverse of this action ran on the same target within
 *       {@code flapWindow}, we are about to undo our own undo. Stop and ask a human.</li>
 *   <li><b>Global rate limit</b>: at most {@code maxActions} automated actions per
 *       {@code rateWindow} fleet-wide. A burst of automation usually means the automation is
 *       confused, or the incident is too big for it.</li>
 * </ol>
 */
public final class OscillationGuard {

    private record Entry(Instant at, RemediationAction action) {
    }

    private final Clock clock;
    private final Duration cooldown;
    private final Duration flapWindow;
    private final Duration rateWindow;
    private final int maxActions;
    private final Deque<Entry> history = new ArrayDeque<>();

    public OscillationGuard(Clock clock, Duration cooldown, Duration flapWindow, Duration rateWindow, int maxActions) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.flapWindow = Objects.requireNonNull(flapWindow, "flapWindow");
        this.rateWindow = Objects.requireNonNull(rateWindow, "rateWindow");
        this.maxActions = Preconditions.checkPositive(maxActions, "maxActions");
    }

    public static OscillationGuard defaults(Clock clock) {
        return new OscillationGuard(clock, Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofMinutes(10), 10);
    }

    /**
     * Checks whether {@code action} may run now. Does NOT record it; call {@link #recordExecuted}
     * after successful execution, so that failed attempts do not consume budget.
     *
     * @return empty string if permitted, otherwise the reason it was blocked
     */
    public synchronized String check(RemediationAction action) {
        Instant now = clock.instant();
        evict(now);
        int inRateWindow = 0;
        RemediationAction inverse = action.inverse();
        for (Entry e : history) {
            Duration age = Duration.between(e.at(), now);
            if (age.compareTo(rateWindow) <= 0) {
                inRateWindow++;
            }
            if (e.action().target().equals(action.target()) && age.compareTo(cooldown) < 0) {
                return "cooldown: " + e.action().describe() + " ran " + age.toSeconds() + "s ago on same target";
            }
            if (e.action().equals(inverse) && age.compareTo(flapWindow) < 0) {
                return "flap: this would undo '" + e.action().describe() + "' from " + age.toSeconds() + "s ago";
            }
        }
        if (inRateWindow >= maxActions) {
            return "rate limit: " + inRateWindow + " automated actions in the last " + rateWindow.toMinutes() + "m";
        }
        return "";
    }

    public synchronized void recordExecuted(RemediationAction action) {
        history.addLast(new Entry(clock.instant(), action));
    }

    private void evict(Instant now) {
        Duration keep = max(max(cooldown, flapWindow), rateWindow);
        Iterator<Entry> it = history.iterator();
        while (it.hasNext()) {
            if (Duration.between(it.next().at(), now).compareTo(keep) > 0) {
                it.remove();
            } else {
                break; // deque is time-ordered
            }
        }
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
