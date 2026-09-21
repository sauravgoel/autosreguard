# AutoSREGuard

[![ci](https://github.com/sauravgoel/autosreguard/actions/workflows/ci.yml/badge.svg)](https://github.com/sauravgoel/autosreguard/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**SRE tooling for reliable, cost-efficient AI inference.**

AutoSREGuard is a control-plane library and service for teams running LLM inference fleets. It
watches what users actually experience, per segment and per tenant, forecasts demand before it
arrives, diagnoses incidents against recent changes, and remediates them with graduated
autonomy: it acts alone only when it is confident, asks first when it is not, and keeps a
reversible audit trail either way.

It is written in Java 21 with **zero runtime dependencies**.

---

## Why

At very large scale, averages lie. A fleet can be at "five nines" while an important customer
is having a terrible day, because their failures are a rounding error in the global number.
Meanwhile, GPU inference adds failure modes classic tooling doesn't see: slow thermal decline,
KV-cache memory pressure, quality regressions that don't show up as errors, and replicas that
take minutes to warm up.

AutoSREGuard turns five reliability lessons into code:

| Lesson | Where it lives |
|---|---|
| Reliability is contextual: segment, don't average | `slo` — per-segment and per-tenant SLOs, burn-rate alerts, `MASKED-BY-AGGREGATE` flag |
| Predict demand; autoscaling is table stakes | `forecast`, `capacity` — Holt-Winters + event signals, warm-up-aware planner, GPU bin packing |
| Automate the repetitive; humans for the novel | `detection`, `diagnosis`, `remediation` — detect → diagnose → remediate with confidence tiers |
| Every automated action is logged, explainable, reversible | `remediation.AuditLog`, `RemediationAction.inverse()`, `OscillationGuard` |
| GenAI infrastructure needs its own playbook | `inference`, `gpu`, `canary`, `shadow` — degradation ladder, cache-aware routing, GPU health, quality canaries |

## Quickstart

Requirements: JDK 21, Maven 3.9+.

```bash
git clone https://github.com/sauravgoel/autosreguard.git
cd autosreguard
mvn -B verify                                        # build + 133 tests
java -jar target/autosreguard-0.1.0-SNAPSHOT.jar --scenario=all
```

Run a single scenario:

```bash
java -jar target/autosreguard-0.1.0-SNAPSHOT.jar --scenario=bad-deploy
```

Run the long-lived service (control loop + admin API) against simulated traffic:

```bash
java -jar target/autosreguard-0.1.0-SNAPSHOT.jar --serve --config=config/autosreguard.properties
curl localhost:9464/v1/slo
curl localhost:9464/metrics
```

Or with Docker:

```bash
docker build -t autosreguard .
docker run --rm -p 9464:9464 autosreguard
```

## The scenarios

Each scenario is deterministic (manual clock, seeded randomness), prints a narrated timeline,
and is asserted in `ScenarioSmokeTest`, so the demo cannot silently drift from the code.

| Scenario | What you will see |
|---|---|
| `hidden-tenant` | 100 enterprise tenants; one fails 4% of requests. The segment SLO is green; that tenant pages, flagged `MASKED-BY-AGGREGATE`. |
| `bad-deploy` | A deploy breaks a model pool. Diagnosis blames it with 0.91 confidence and AutoSREGuard rolls it back in about a minute with no human. A second bad config change minutes later is held by the cooldown guardrail and a human is paged; it is proposed and executed only after the cooldown. |
| `gpu-degradation` | One GPU node slowly overheats: AutoSREGuard proposes a drain and executes it when the veto window lapses. Another node throws an uncorrectable ECC error: drained immediately. Workloads are re-packed onto healthy nodes. |
| `predictive-scaling` | A product launch doubles demand. Reactive scaling is short for two intervals while replicas warm up; the event-aware forecaster has capacity ready in advance. |
| `graceful-degradation` | Pressure ramps on the flagship model. Consumer traffic moves to a quantized variant first; enterprise keeps the flagship until its latency budget is truly at risk. A tripped breaker degrades rather than errors. Includes shedding and prefix-cache routing comparisons. |
| `canary` | A new model is faster and equally reliable but worse. Error/latency checks would promote it; the quality gate rolls it back. |
| `shadow` | A proposed, more aggressive autonomy policy runs in shadow. It disagrees with the incumbent 20% of the time and stays in shadow; a minor retune agrees 99% of the time and becomes eligible for review. |

## Architecture

```
            telemetry (outcomes, signals, GPU stats)          change events (CI/CD, config)
                         |                                              |
     +-------------------v-------------------+                          |
     |  SegmentedSloTracker   Detectors      |                          |
     |  GpuHealthScorer                      |                          |
     +-------------------+-------------------+                          |
                         | anomalies                                    |
                 +-------v--------+     service graph            +------v------+
                 | RootCauseAnalyzer <---------------------------+  ChangeLog  |
                 +-------+--------+                              +------^------+
                         | ranked hypotheses + evidence                 | our own rollbacks
                 +-------v------------------------------------------+   | are recorded here
                 | RemediationEngine                                |   |
                 |  planner -> ConfidencePolicy (tier) -> guard     +---+
                 |  AUTO | SUGGEST (veto window) | PAGE             |
                 +---+----------------+----------------+-----------+
                     |                |                |
               ActionExecutor      Notifier         AuditLog (JSON lines)
               (your infra)     (your pager/chat)

  Serving path (in-process library):  LoadShedder -> LatencyAwareRouter -> PrefixAffinityBalancer
  Planning path:                      EventAwareForecaster -> CapacityPlanner -> BinPacker
  Rollout path:                       CanaryAnalyzer, ShadowEvaluator
```

`AutoSREGuard` (in `runtime`) runs the control loop: evaluate SLOs, collect anomalies, diagnose,
make at most one decision per cycle, and tick pending proposals. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full design and
[`docs/adr`](docs/adr) for the decisions behind it.

## Packages

| Package | Contents |
|---|---|
| `core` | `Segment`, `TenantId`, injectable clocks, preconditions, a tiny JSON writer |
| `slo` | `SlidingWindowCounter`, `SloDefinition` (availability / TTFT / TPOT), `BurnRatePolicy`, `SegmentedSloTracker` |
| `forecast` | `HoltWintersForecaster`, `EventAwareForecaster`, `EventSignal` |
| `capacity` | `CapacityPlanner`, `GpuProfile`, `BinPacker` |
| `detection` | `EwmaAnomalyDetector`, signal catalogue |
| `diagnosis` | `ServiceGraph`, `ChangeLog`, `RootCauseAnalyzer`, `Hypothesis` |
| `remediation` | Actions and inverses, `ConfidencePolicy`, `OscillationGuard`, `RemediationEngine`, `AuditLog` |
| `inference` | `LatencyAwareRouter`, `LiveLatencyModel`, `CircuitBreaker`, `LoadShedder`, `PrefixAffinityBalancer` |
| `gpu` | `GpuHealthScorer` from ECC / XID / thermal / throttle telemetry |
| `canary`, `shadow` | Quality-aware canary verdicts; shadow-mode policy evaluation |
| `metrics`, `server`, `config`, `runtime` | Prometheus exposition, admin API, configuration, control loop |
| `demo` | Scenarios, simulated fleet, CLI entrypoint |

## Integrating with a real fleet

AutoSREGuard is intentionally agnostic about your infrastructure. Implement three interfaces:

- **`ActionExecutor`** — apply a `RemediationAction`: call Argo/Spinnaker for rollbacks, your
  config service for reverts, the Kubernetes API for `cordon`/`drain` and scaling, your
  gateway for shedding. Throw `RemediationException` on failure; it is audited and paged.
- **`Notifier`** — deliver `INFO`, `APPROVAL_REQUESTED` and `PAGE` notifications to Slack,
  PagerDuty, Opsgenie, etc. Include the action id so responders can approve or veto.
- **`FleetState`** — read current replica counts and shedding levels so plans are relative
  to reality.

Then feed it data: `recordOutcome(...)` per request (or pre-aggregated), `observeSignal(...)`
for component metrics, `observeGpu(...)` for DCGM-style telemetry, and `ChangeLog.record(...)`
from your deploy pipeline. The router, shedder and balancer are plain libraries you can embed
in a gateway.

## Admin API

| Method | Path | Purpose |
|---|---|---|
| GET | `/healthz` | Liveness |
| GET | `/metrics` | Prometheus exposition (SLO budget/burn, remediation counts, cycles) |
| GET | `/v1/slo` | Latest SLO statuses as JSON |
| GET | `/v1/audit` | Recent audit records |
| POST | `/v1/remediations/{id}/approve?by=you` | Execute a pending proposal now |
| POST | `/v1/remediations/{id}/veto?by=you&reason=...` | Cancel a pending proposal |
| POST | `/v1/remediations/{id}/revert?by=you` | Run the inverse of an executed action |

`by` is mandatory: every human action is attributed. The API has no built-in auth; see
[`SECURITY.md`](SECURITY.md).

## Configuration

All settings live in [`config/autosreguard.properties`](config/autosreguard.properties) and can be
overridden by environment variables (`remediation.dry-run` → `AUTOSREGUARD_REMEDIATION_DRY_RUN`).
Durations accept `500ms`, `30s`, `5m`, `6h`, `1d` or ISO-8601.

**Safety defaults:** `remediation.dry-run=true` (decide and audit, change nothing), admin API
bound to loopback, 5-minute per-target cooldown, 30-minute flap protection, at most 10
automated actions per 10 minutes, and higher confidence required for higher blast-radius
actions.

A sensible rollout: run in dry-run for a few weeks, compare the audit log with what your
on-call actually did, tune thresholds (the `shadow` package helps here), then enable acting
mode for low-blast-radius actions first.

## Non-goals

- **Not a serving engine.** Continuous batching, paged attention and KV-cache management
  belong in engines like vLLM, TensorRT-LLM or SGLang. AutoSREGuard routes between and reasons
  about them.
- **Not a metrics store or dashboarding tool.** It exports Prometheus metrics and consumes
  whatever your pipeline already collects.
- **Not a replacement for on-call.** It handles the repetitive incidents so humans can focus
  on novel ones.

## Development

```bash
mvn -B verify
```

The build compiles with `-Xlint:all`. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for ground
rules (no new runtime dependencies, every action reversible, deterministic tests).

## License

[MIT](LICENSE) © Saurav Goel
