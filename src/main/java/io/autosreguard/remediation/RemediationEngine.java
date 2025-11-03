package io.autosreguard.remediation;

import io.autosreguard.diagnosis.Hypothesis;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The remediation layer of autonomous SRE: turns diagnoses into safe, audited actions.
 *
 * <h2>Lifecycle of a hypothesis</h2>
 * <pre>
 *  Hypothesis ──plan──▶ action? ──no──▶ ESCALATED (page with evidence)
 *                          │yes
 *                          ▼
 *              tier = policy(confidence, blastRadius)
 *                          │
 *        guardrail check (cooldown / flap / rate) ──blocked──▶ SUPPRESSED + page
 *                          │ok
 *        ┌─────────────────┼────────────────────┐
 *   AUTO_REMEDIATE   SUGGEST_AND_NOTIFY     PAGE_AND_ASSIST
 *   execute now      PROPOSED; runs at       ESCALATED; page with
 *   EXECUTED         veto deadline unless    hypotheses attached
 *                    VETOED / APPROVED
 * </pre>
 *
 * <h2>Threading</h2>
 * Public methods are synchronized; the engine is driven by one control loop plus occasional
 * human calls (approve/veto/revert) from the admin API.
 */
public final class RemediationEngine {

    /** Actor name recorded for automated transitions. */
    public static final String SYSTEM_ACTOR = "autosreguard";

    private final RemediationPlanner planner;
    private final ConfidencePolicy policy;
    private final OscillationGuard guard;
    private final ActionExecutor executor;
    private final Notifier notifier;
    private final AuditLog audit;
    private final Clock clock;

    private record Pending(String actionId, RemediationAction action, Hypothesis hypothesis, Instant deadline) {
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();
    /** Executed actions by id, so they can be reverted. */
    private final Map<String, RemediationAction> executed = new LinkedHashMap<>();
    private long idCounter;

    public RemediationEngine(RemediationPlanner planner, ConfidencePolicy policy, OscillationGuard guard,
                             ActionExecutor executor, Notifier notifier, AuditLog audit, Clock clock) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Processes one hypothesis. Call with the top hypothesis from diagnosis (or each above a floor). */
    public synchronized RemediationOutcome handle(Hypothesis h) {
        String id = nextId();
        Optional<RemediationAction> planned = planner.plan(h);
        if (planned.isEmpty()) {
            write(id, AuditRecord.Status.ESCALATED, AutonomyTier.PAGE_AND_ASSIST, h, null, SYSTEM_ACTOR,
                    "no validated playbook for " + h.kind());
            notifier.notify(new Notifier.Notification(Notifier.Urgency.PAGE,
                    "No automated playbook for " + h.kind() + " on " + h.subject(), id, h.evidence()));
            return new RemediationOutcome(id, AutonomyTier.PAGE_AND_ASSIST, AuditRecord.Status.ESCALATED,
                    Optional.empty(), "escalated: no playbook");
        }
        RemediationAction action = planned.get();
        AutonomyTier tier = policy.tierFor(h.confidence(), action.blastRadius());

        // Guardrails apply to anything the system might execute on its own.
        if (tier != AutonomyTier.PAGE_AND_ASSIST) {
            String blocked = guard.check(action);
            if (!blocked.isEmpty()) {
                write(id, AuditRecord.Status.SUPPRESSED, AutonomyTier.PAGE_AND_ASSIST, h, action, SYSTEM_ACTOR, blocked);
                notifier.notify(new Notifier.Notification(Notifier.Urgency.PAGE,
                        "Guardrail blocked '" + action.describe() + "': " + blocked, id, h.evidence()));
                return new RemediationOutcome(id, AutonomyTier.PAGE_AND_ASSIST, AuditRecord.Status.SUPPRESSED,
                        Optional.of(action), blocked);
            }
        }

        return switch (tier) {
            case AUTO_REMEDIATE -> executeNow(id, action, h, tier, SYSTEM_ACTOR, "auto-remediated");
            case SUGGEST_AND_NOTIFY -> {
                Instant deadline = clock.instant().plus(policy.vetoWindow());
                pending.put(id, new Pending(id, action, h, deadline));
                write(id, AuditRecord.Status.PROPOSED, tier, h, action, SYSTEM_ACTOR,
                        "will execute at " + deadline + " unless vetoed");
                notifier.notify(new Notifier.Notification(Notifier.Urgency.APPROVAL_REQUESTED,
                        "Proposed: " + action.describe() + " (auto-executes in "
                                + policy.vetoWindow().toSeconds() + "s unless vetoed)", id, h.evidence()));
                yield new RemediationOutcome(id, tier, AuditRecord.Status.PROPOSED, Optional.of(action),
                        "proposed; veto window " + policy.vetoWindow());
            }
            case PAGE_AND_ASSIST -> {
                write(id, AuditRecord.Status.ESCALATED, tier, h, action, SYSTEM_ACTOR,
                        "confidence below suggest threshold; candidate action attached");
                notifier.notify(new Notifier.Notification(Notifier.Urgency.PAGE,
                        String.format(java.util.Locale.ROOT, "Low-confidence diagnosis (%.2f): %s on %s; candidate: %s",
                                h.confidence(), h.kind(), h.subject(), action.describe()), id, h.evidence()));
                yield new RemediationOutcome(id, tier, AuditRecord.Status.ESCALATED, Optional.of(action),
                        "paged human with candidate action");
            }
        };
    }

    /**
     * Executes proposals whose veto window has elapsed. Call from the control loop.
     *
     * @return outcomes for proposals executed (or blocked) this tick
     */
    public synchronized List<RemediationOutcome> tick() {
        Instant now = clock.instant();
        List<RemediationOutcome> out = new ArrayList<>();
        List<Pending> due = pending.values().stream().filter(p -> !now.isBefore(p.deadline())).toList();
        for (Pending p : due) {
            pending.remove(p.actionId());
            // Re-check guardrails: the world may have changed during the veto window.
            String blocked = guard.check(p.action());
            if (!blocked.isEmpty()) {
                write(p.actionId(), AuditRecord.Status.SUPPRESSED, AutonomyTier.SUGGEST_AND_NOTIFY, p.hypothesis(),
                        p.action(), SYSTEM_ACTOR, blocked);
                notifier.notify(new Notifier.Notification(Notifier.Urgency.PAGE,
                        "Guardrail blocked proposed action at deadline: " + blocked, p.actionId(), p.hypothesis().evidence()));
                out.add(new RemediationOutcome(p.actionId(), AutonomyTier.SUGGEST_AND_NOTIFY,
                        AuditRecord.Status.SUPPRESSED, Optional.of(p.action()), blocked));
                continue;
            }
            out.add(executeNow(p.actionId(), p.action(), p.hypothesis(), AutonomyTier.SUGGEST_AND_NOTIFY,
                    SYSTEM_ACTOR, "veto window elapsed"));
        }
        return out;
    }

    /** A human approves a proposal early. */
    public synchronized RemediationOutcome approve(String actionId, String who) {
        Pending p = pending.remove(actionId);
        if (p == null) {
            throw new IllegalArgumentException("no pending proposal " + actionId);
        }
        write(actionId, AuditRecord.Status.APPROVED, AutonomyTier.SUGGEST_AND_NOTIFY, p.hypothesis(), p.action(), who, "");
        return executeNow(actionId, p.action(), p.hypothesis(), AutonomyTier.SUGGEST_AND_NOTIFY, who, "approved");
    }

    /** A human vetoes a proposal. */
    public synchronized void veto(String actionId, String who, String reason) {
        Pending p = pending.remove(actionId);
        if (p == null) {
            throw new IllegalArgumentException("no pending proposal " + actionId);
        }
        write(actionId, AuditRecord.Status.VETOED, AutonomyTier.SUGGEST_AND_NOTIFY, p.hypothesis(), p.action(), who, reason);
    }

    /**
     * Undoes an executed action by running its inverse. Human-initiated reverts bypass the
     * oscillation guard: a person deliberately undoing automation is not a remediation loop.
     */
    public synchronized RemediationOutcome revert(String actionId, String who) throws RemediationException {
        RemediationAction original = executed.remove(actionId);
        if (original == null) {
            throw new IllegalArgumentException("no executed action " + actionId);
        }
        RemediationAction inverse = original.inverse();
        executor.execute(inverse);
        AuditRecord last = audit.latest(actionId).orElseThrow();
        audit.append(new AuditRecord(audit.nextSeq(), actionId, clock.instant(), AuditRecord.Status.REVERTED,
                last.tier(), last.confidence(), last.subject(), inverse.describe(), original.describe(), who,
                "reverted by " + who, last.evidence()));
        return new RemediationOutcome(actionId, last.tier(), AuditRecord.Status.REVERTED, Optional.of(inverse),
                "reverted");
    }

    public synchronized List<String> pendingIds() {
        return List.copyOf(pending.keySet());
    }

    private RemediationOutcome executeNow(String id, RemediationAction action, Hypothesis h, AutonomyTier tier,
                                          String actor, String note) {
        try {
            executor.execute(action);
        } catch (RemediationException | RuntimeException e) {
            write(id, AuditRecord.Status.FAILED, tier, h, action, actor, "execution failed: " + e.getMessage());
            notifier.notify(new Notifier.Notification(Notifier.Urgency.PAGE,
                    "Remediation FAILED: " + action.describe() + ": " + e.getMessage(), id, h.evidence()));
            return new RemediationOutcome(id, tier, AuditRecord.Status.FAILED, Optional.of(action), e.getMessage());
        }
        guard.recordExecuted(action);
        executed.put(id, action);
        write(id, AuditRecord.Status.EXECUTED, tier, h, action, actor, note);
        notifier.notify(new Notifier.Notification(Notifier.Urgency.INFO,
                "Executed: " + action.describe() + " (" + note + ")", id, h.evidence()));
        return new RemediationOutcome(id, tier, AuditRecord.Status.EXECUTED, Optional.of(action), note);
    }

    private void write(String id, AuditRecord.Status status, AutonomyTier tier, Hypothesis h,
                       RemediationAction action, String actor, String note) {
        audit.append(new AuditRecord(audit.nextSeq(), id, clock.instant(), status, tier, h.confidence(), h.subject(),
                action == null ? "" : action.describe(),
                action == null ? "" : action.inverse().describe(),
                actor, note, h.evidence()));
    }

    private String nextId() {
        return String.format(java.util.Locale.ROOT, "rem-%06d", ++idCounter);
    }
}
