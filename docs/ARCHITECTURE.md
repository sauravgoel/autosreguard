# AutoSREGuard architecture

This document explains how AutoSREGuard is put together and why. For the decisions themselves,
see the ADRs in [`adr/`](adr).

## Design principles

1. **Measure what users experience, at the granularity they experience it.** SLOs are
   evaluated per segment *and* per tenant. An aggregate cannot hide a failing customer.
2. **Autonomy is earned, graduated and reversible.** Confidence decides whether the system
   acts alone, proposes with a veto window, or pages a human. Every action has an inverse.
3. **Guardrails beat cleverness.** Cooldowns, flap detection and a global rate limit sit in
   front of every automated action, because the worst incident is the one the automation
   makes worse.
4. **Deterministic and testable.** Every time-dependent component takes a `java.time.Clock`.
   Scenarios and tests run on a manual clock with seeded randomness.
5. **Small supply chain.** No runtime dependencies. The JDK provides HTTP,
   configuration (`Properties`), logging and concurrency.

## Data flow

```
 outcomes --> SegmentedSloTracker ----------------------------+
 signals  --> EwmaAnomalyDetector --+                         |
 GPU      --> GpuHealthScorer ------+--> active anomalies     |
                                            |                 v
                                   RootCauseAnalyzer     SLO statuses -> metrics / API
                                   (graph + ChangeLog)
                                            |
                                   ranked Hypotheses
                                            |
                                   RemediationEngine --> ActionExecutor, Notifier, AuditLog
```

### 1. SLO evaluation (`slo`)

`SlidingWindowCounter` keeps ring-buffered good/total counts per bucket (default one
minute) over the longest alert window (24h). `SegmentedSloTracker` keeps one counter per
`(SLO, segment)` and one per `(SLO, tenant)`.

`BurnRatePolicy` implements multi-window, multi-burn-rate alerting: a rule fires only if both
its long and short windows burn above the threshold (e.g. 14.4x over 1h *and* 5m), which
gives fast detection without flapping. `minEvents` stops low-traffic tenants paging on a
single error.

After evaluation, a tenant status that fires while its segment does not fire at the same or
higher severity is marked `maskedByAggregate`. That flag is the core of the "segment, don't
average" lesson: it tells the responder that the dashboard is green *and* that this customer
is hurting.

Tenant series are always tracked, but tenant metrics are only exported while firing, to keep
Prometheus cardinality bounded.

### 2. Detection (`detection`, `gpu`)

`EwmaAnomalyDetector` keeps an exponentially weighted mean and variance, requires a warm-up,
a z-score threshold and *consecutive* suspicious samples. Anomalous samples do not update
the baseline, so a sustained incident does not become the new normal.

`GpuHealthScorer` maintains a decaying penalty per node from correctable ECC deltas,
temperature above a soft limit and throttle ratio. Uncorrectable ECC or XID errors are
sticky hard faults until a human clears them. Nodes are graded `HEALTHY`, `DEGRADED` or
`FAILING`; only `FAILING` becomes an anomaly (with 0.97 confidence for hard faults).

### 3. Diagnosis (`diagnosis`)

`RootCauseAnalyzer` combines three kinds of evidence:

- **Topology.** A component is a root candidate if it is anomalous and none of its
  (transitive) dependencies are. Callers of a broken service are symptoms.
- **Change correlation.** Changes on the component inside the correlation window are scored
  by proximity (exponential decay), coverage (how much of the anomalous set it explains) and
  anomaly strength. Changes authored by AutoSREGuard itself, and changes superseded by a later
  change to the same key, are skipped.
- **Signal type.** Hardware signals produce `HARDWARE_DEGRADATION`; saturation signals
  produce `CAPACITY_EXHAUSTION`; a root with no explaining change is a `COMPONENT_FAULT`.

Each `Hypothesis` carries a confidence and a list of evidence strings. The evidence goes into
notifications and the audit log, so every decision can be explained.

### 4. Remediation (`remediation`)

`RemediationPlanner` maps a hypothesis to at most one action:

| Hypothesis | Action |
|---|---|
| `BAD_CHANGE` (deployment / model rollout) | `Rollback` |
| `BAD_CHANGE` (config) | `RevertConfig` |
| `CAPACITY_EXHAUSTION` | `ScaleReplicas` (x1.5, capped) |
| `HARDWARE_DEGRADATION` | `DrainNode` |
| `COMPONENT_FAULT` | `ShedLoad` (+25%, lowest-priority traffic first) |

`ConfidencePolicy` picks a tier. Higher blast radius raises the bar for acting alone
(LOW +0, MEDIUM +0.03, HIGH +0.07 over the auto threshold).

`OscillationGuard` blocks an action if the same target was acted on within the cooldown,
if the action would undo a recent action (flap), or if the global rate limit is reached. A
blocked action becomes a page, never a silent drop.

`RemediationEngine` executes, proposes or escalates, and writes an `AuditRecord` for every
state change: `EXECUTED`, `PROPOSED`, `APPROVED`, `VETOED`, `ESCALATED`, `SUPPRESSED`,
`FAILED`, `REVERTED`. Records include confidence, evidence, the action, its inverse and the
actor. Proposals execute when their veto window elapses unless a human vetoes them first,
and guardrails are re-checked at that moment.

When not in dry-run, `ChangeRecordingExecutor` writes AutoSREGuard's own rollbacks and reverts to
the `ChangeLog` as automated changes, so diagnosis sees what is actually live.

### 5. The control loop (`runtime`)

Each cycle of `AutoSREGuard.runCycle()`:

1. evaluates SLOs and publishes metrics;
2. expires anomalies older than the TTL;
3. diagnoses the active anomalies;
4. handles **at most one** hypothesis above the minimum confidence, skipping incidents
   handled within the re-handle window (incident key = kind + subject + change id);
5. ticks the engine so due proposals execute.

After any executed action, whether immediate or at the end of a veto window, all active
anomalies are cleared so the next cycle judges fresh data. Problems that persist resurface
within one detector sample, and the guard then turns a repeat into a page.

One decision per cycle is deliberate: it lets the effect of an action show up before the
next one is taken.

## Serving-path components (`inference`)

These are libraries for a gateway or router, not part of the control loop:

- `LoadShedder` maps one shedding level onto segments in priority order (consumer, then API,
  then enterprise). Decisions hash the request id, so retries are treated consistently.
- `LatencyAwareRouter` walks a quality-ordered ladder of `ModelVariant`s, skipping variants
  that are below the request's quality floor, too small for its context, behind an open
  `CircuitBreaker`, saturated, over its latency budget, or (for consumer traffic) above the
  pressure threshold. Degradation is reported, not hidden.
- `LiveLatencyModel` blends static variant profiles with observed TTFT/TPOT and inflates
  queueing delay by 1/(1-ρ).
- `PrefixAffinityBalancer` uses consistent hashing with bounded loads so requests sharing a
  prompt prefix land on replicas that already hold it in KV cache, while no replica exceeds
  `c x average` in-flight load.

## Planning components (`forecast`, `capacity`)

- `HoltWintersForecaster` (additive trend + seasonality) initialises from two full seasons,
  imputes gaps, and produces prediction intervals that widen with the horizon.
- `EventAwareForecaster` divides observations by known event multipliers before learning (so
  a launch does not pollute the baseline) and multiplies forecasts during scheduled events.
- `CapacityPlanner` sizes for the forecast upper bound over `warmup + lookahead`, never
  below current demand, and scales down by at most a fixed fraction per cycle.
- `BinPacker` places replicas best-fit-decreasing on healthy nodes, with anti-affinity per
  replica group and VRAM headroom.

## Rollout components (`canary`, `shadow`)

- `CanaryAnalyzer` compares arms on error rate (two-proportion z-test), p95 latency ratio and
  mean quality score (Welch-style t). Any clear failure rolls back immediately; promotion
  requires enough data on every check.
- `ShadowEvaluator` runs a candidate function beside the incumbent, returns only the
  incumbent's result, and reports agreement, candidate errors and recent divergences.
  Passing its gate means "ready for human review".

## Threading model

`SegmentedSloTracker`, detectors, `RemediationEngine`, `AuditLog`, `CircuitBreaker` and
`PrefixAffinityBalancer` are thread-safe. `AutoSREGuard.runCycle()` is synchronized and runs on a
single scheduler thread. The admin server uses a small fixed pool. Ingestion methods
(`recordOutcome`, `observeSignal`, `observeGpu`) may be called from any thread.

## Extension points

`ActionExecutor`, `Notifier`, `FleetState`, `DemandForecaster` and `AnomalyDetector` are
interfaces. Everything else can be used directly as a library.
