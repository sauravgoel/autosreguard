package io.autosreguard.remediation;

import io.autosreguard.core.Json;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One immutable entry in the remediation audit trail.
 *
 * <p>Autonomy without auditability is dangerous. Every automated action must be:
 * <b>logged</b> (this record exists), <b>explainable</b> ({@link #evidence} and
 * {@link #confidence}), and <b>reversible</b> ({@link #inverse}). Non-negotiable.
 *
 * <p>State transitions append new records with the same {@link #actionId}; nothing is ever
 * updated in place.
 *
 * @param seq        monotonically increasing sequence number
 * @param actionId   groups all records about one proposed action
 * @param at         when this record was written
 * @param status     lifecycle state at this point
 * @param tier       autonomy tier chosen
 * @param confidence diagnosis confidence
 * @param subject    component diagnosed
 * @param action     the action (description), or empty when none was possible
 * @param inverse    how to undo it
 * @param actor      who caused this transition: "autosreguard" or a human identity
 * @param note       free-text detail (e.g. why a guardrail blocked it)
 * @param evidence   reasoning chain from diagnosis
 */
public record AuditRecord(
        long seq,
        String actionId,
        Instant at,
        Status status,
        AutonomyTier tier,
        double confidence,
        String subject,
        String action,
        String inverse,
        String actor,
        String note,
        List<String> evidence) {

    public AuditRecord {
        Objects.requireNonNull(actionId);
        Objects.requireNonNull(at);
        Objects.requireNonNull(status);
        Objects.requireNonNull(tier);
        Objects.requireNonNull(subject);
        Objects.requireNonNull(action);
        Objects.requireNonNull(inverse);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(note);
        evidence = List.copyOf(evidence);
    }

    public enum Status {
        /** Executed automatically or after approval/expired veto window. */
        EXECUTED,
        /** Proposed, awaiting veto window. */
        PROPOSED,
        /** Human approved a proposal early. */
        APPROVED,
        /** Human vetoed a proposal. */
        VETOED,
        /** Escalated to a human (low confidence or no applicable action). */
        ESCALATED,
        /** A guardrail blocked an action that would otherwise have run. */
        SUPPRESSED,
        /** Execution failed. */
        FAILED,
        /** The action was undone via its inverse. */
        REVERTED
    }

    /** JSON Lines representation for durable sinks and the admin API. */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("actionId", actionId);
        m.put("at", at.toString());
        m.put("status", status.name());
        m.put("tier", tier.name());
        m.put("confidence", confidence);
        m.put("subject", subject);
        m.put("action", action);
        m.put("inverse", inverse);
        m.put("actor", actor);
        m.put("note", note);
        m.put("evidence", evidence);
        return Json.write(m);
    }
}
