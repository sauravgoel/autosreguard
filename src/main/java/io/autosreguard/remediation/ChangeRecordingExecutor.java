package io.autosreguard.remediation;

import io.autosreguard.diagnosis.ChangeEvent;
import io.autosreguard.diagnosis.ChangeLog;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorator that records AutoSREGuard's own version/config changes into the {@link ChangeLog}.
 *
 * <p>Without this, diagnosis would still see the original bad deploy as "live" after we rolled
 * it back, and could propose rolling it back again. With it, the rollback supersedes the deploy
 * and root-cause analysis reasons about what is actually running.
 */
public final class ChangeRecordingExecutor implements ActionExecutor {

    private final ActionExecutor delegate;
    private final ChangeLog changes;
    private final Clock clock;
    private final AtomicLong seq = new AtomicLong();

    public ChangeRecordingExecutor(ActionExecutor delegate, ChangeLog changes, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void execute(RemediationAction action) throws RemediationException {
        delegate.execute(action); // only record changes that actually happened
        switch (action) {
            case RemediationAction.Rollback r -> changes.record(new ChangeEvent(
                    "autosreguard-rollback-" + seq.incrementAndGet(), ChangeEvent.Kind.DEPLOYMENT, r.target(),
                    r.fromVersion(), r.toVersion(), clock.instant(), ChangeEvent.AUTOMATION_AUTHOR));
            case RemediationAction.RevertConfig rc -> changes.record(new ChangeEvent(
                    rc.key(), ChangeEvent.Kind.CONFIG, rc.target(), rc.badValue(), rc.goodValue(),
                    clock.instant(), ChangeEvent.AUTOMATION_AUTHOR));
            default -> { /* capacity/traffic actions are not versioned changes */ }
        }
    }
}
