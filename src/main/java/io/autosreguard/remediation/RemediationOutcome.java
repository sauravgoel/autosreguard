package io.autosreguard.remediation;

import java.util.Optional;

/**
 * What the engine did with a hypothesis.
 *
 * @param actionId audit id (use it to approve, veto, or revert)
 * @param tier     autonomy tier applied
 * @param status   resulting audit status
 * @param action   the action, if one was planned
 * @param message  short explanation
 */
public record RemediationOutcome(String actionId, AutonomyTier tier, AuditRecord.Status status,
                                 Optional<RemediationAction> action, String message) {
}
