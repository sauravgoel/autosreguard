# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed
- Circuit breakers ignore results that arrive while OPEN, so late failures from calls admitted
  before the trip no longer extend the cooldown, and in HALF_OPEN only as many successes as
  probes handed out can close the breaker.

## [0.1.0] - 2026-09-21

### Added
- Segmented SLO tracking with per-tenant series, multi-window burn-rate alerting and
  `MASKED-BY-AGGREGATE` detection for tenants failing under a green segment.
- Holt-Winters demand forecasting with scheduled event multipliers; predictive capacity
  planner that sizes for replica warm-up plus lookahead and scales down gradually.
- GPU-aware bin packing (best-fit decreasing, anti-affinity, health filtering, VRAM headroom).
- EWMA anomaly detection, topology- and change-aware root-cause analysis.
- Confidence-tiered remediation engine (auto / suggest-with-veto / page-and-assist) with
  blast-radius weighting, oscillation guard (cooldown, flap, rate limit) and an append-only
  JSON-lines audit log where every action records its inverse.
- LLM serving controls: latency-aware router with a degradation ladder, circuit breakers,
  priority load shedding, prefix-cache-affinity balancing with bounded loads.
- GPU health scoring from ECC, XID, thermal and throttle telemetry.
- Quality-aware canary analysis and shadow-mode evaluation.
- Prometheus metrics, admin HTTP API, properties + environment configuration.
- Seven deterministic demo scenarios, 133 unit and end-to-end tests, CI and container image.

### Fixed (during development)
- Proposals executed at the end of their veto window now clear active anomalies, like
  auto-remediations do, so a fixed incident is not re-diagnosed from stale evidence.

[Unreleased]: https://github.com/sauravgoel/autosreguard/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/sauravgoel/autosreguard/releases/tag/v0.1.0
