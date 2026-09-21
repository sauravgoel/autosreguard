# ADR-0002: Confidence-tiered autonomy with blast-radius weighting

- Status: Accepted
- Date: 2025-11-03

## Context

Fully manual remediation is too slow at scale; fully automatic remediation is dangerous
because diagnoses are sometimes wrong. A tiered model balances the two: act autonomously on high
confidence, suggest and notify on medium, page and assist on low.

## Decision

- Three tiers: `AUTO_REMEDIATE` (confidence >= 0.85), `SUGGEST_AND_NOTIFY` (>= 0.6, executes
  after a 3-minute veto window unless vetoed), `PAGE_AND_ASSIST` (below, with the candidate
  action attached).
- The auto threshold rises with blast radius: +0.03 for MEDIUM, +0.07 for HIGH.
- Suggested actions execute at the deadline by default ("silence is consent"), because the
  scenarios that reach this tier are ones where waiting costs error budget. Teams that prefer
  explicit approval can set the veto window very long and approve through the API.
- Every action implements `inverse()`; every decision is audited with its evidence.

## Consequences

- Repetitive incidents (bad deploys, failing GPUs) resolve in minutes without a human.
- Humans remain in the loop for ambiguous cases and can always revert.
- Thresholds must be calibrated per environment; `ShadowEvaluator` supports evaluating a
  proposed policy against the incumbent before switching.
