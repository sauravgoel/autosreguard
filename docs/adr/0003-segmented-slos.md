# ADR-0003: Segmented and per-tenant SLOs

- Status: Accepted
- Date: 2025-03-21

## Context

Aggregate availability at large scale hides concentrated pain: a single enterprise customer
can account for a large share of failing users while the global number looks excellent.

## Decision

- SLOs are defined per segment (enterprise, API, consumer) with segment-appropriate targets.
- Every tenant in a segment gets its own series and is evaluated with the same burn-rate
  rules, subject to a minimum event count.
- A firing tenant whose segment is not firing at the same severity is flagged
  `maskedByAggregate`, so routing can send it to the account's escalation path.
- Tenant metrics are exported only while firing, bounding cardinality.

## Consequences

- Memory grows with tenants x SLOs x buckets. With one-minute buckets over 24h this is
  roughly 35 KB per series; operators with very many tenants should widen buckets or shard.
- Small tenants below `minEvents` cannot alert; that is intentional.
