package io.autosreguard.diagnosis;

import java.time.Instant;
import java.util.Objects;

/**
 * Something that changed in production. Most incidents are caused by change, so a
 * complete, queryable change log is the single most valuable input to root-cause analysis.
 *
 * @param id        unique change id (deploy id, rollout id); for {@link Kind#CONFIG} changes this is
 *                  the config key, so configuration healing knows what to revert
 * @param kind      what kind of change
 * @param component what it changed
 * @param fromValue previous version/value (needed to build the inverse action)
 * @param toValue   new version/value
 * @param at        when the change took effect
 * @param author    who or what made it (a human, a pipeline, or AutoSREGuard itself)
 */
public record ChangeEvent(String id, Kind kind, String component, String fromValue, String toValue,
                          Instant at, String author) {

    /**
     * Author recorded for changes AutoSREGuard itself makes. Remediations restore a
     * previously-known-good state, so diagnosis does not treat them as candidate causes; if a
     * remediation itself misbehaves, the oscillation guard and humans handle it.
     */
    public static final String AUTOMATION_AUTHOR = "autosreguard";

    public ChangeEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(fromValue, "fromValue");
        Objects.requireNonNull(toValue, "toValue");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(author, "author");
    }

    public boolean isAutomated() {
        return AUTOMATION_AUTHOR.equals(author);
    }

    /**
     * Whether {@code later} replaces this change's effect: same component, same kind, and for
     * config the same key. A deploy superseded by a rollback can no longer be the live cause.
     */
    public boolean isSupersededBy(ChangeEvent later) {
        return later.at().isAfter(at)
                && later.component().equals(component)
                && later.kind() == kind
                && (kind != Kind.CONFIG || later.id().equals(id));
    }

    public enum Kind {
        /** New binary/container version. */
        DEPLOYMENT,
        /** Runtime configuration or feature flag. */
        CONFIG,
        /** New model weights or model version behind the same service. */
        MODEL_ROLLOUT
    }
}
