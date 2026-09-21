# ADR-0004: Oscillation guard in front of all automation

- Status: Accepted
- Date: 2025-11-03

## Context

Automated remediation can create loops: roll back, detect a symptom of the rollback, roll
forward, and so on; or take many actions at once during a large incident.

## Decision

Before any action the system might take on its own (auto or at the end of a veto window):

- **Cooldown** (default 5m): no second action on the same target.
- **Flap window** (default 30m): no action that is the inverse of a recent one.
- **Rate limit** (default 10 per 10m): cap on automated actions fleet-wide.

A blocked action is recorded as `SUPPRESSED` and pages a human. After executing an action the
control loop also clears active anomalies and makes at most one decision per cycle.

## Consequences

- A second genuine problem on the same target within the cooldown needs a human, which the
  `bad-deploy` scenario demonstrates. That delay is the price of never amplifying an incident.
