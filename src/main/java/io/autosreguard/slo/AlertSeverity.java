package io.autosreguard.slo;

/**
 * How urgently a human needs to look.
 *
 * <p>Only {@link #PAGE} should wake someone up. Everything else is a ticket to be handled in
 * business hours. Mixing the two is the fastest route to alert fatigue.
 */
public enum AlertSeverity {
    NONE,
    TICKET,
    PAGE;

    public boolean atLeast(AlertSeverity other) {
        return this.ordinal() >= other.ordinal();
    }
}
