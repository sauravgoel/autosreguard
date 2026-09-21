# ADR-0001: Zero runtime dependencies

- Status: Accepted
- Date: 2024-09-23

## Context

AutoSREGuard runs during incidents and can change production. Its own availability and
integrity matter more than developer convenience. Every third-party library adds
vulnerabilities to patch, transitive version conflicts and startup cost.

## Decision

The runtime depends only on the JDK (21). HTTP uses `com.sun.net.httpserver`, configuration
uses `java.util.Properties`, logging uses `System.Logger`, and JSON output uses a small
write-only encoder. JUnit is permitted at test scope.

## Consequences

- A tiny, auditable supply chain and a jar that starts in milliseconds.
- We maintain small pieces ourselves (JSON writer, Prometheus text format). They are
  deliberately minimal and tested.
- Integrations that need SDKs (Kubernetes, PagerDuty, Slack) live in separate modules that
  implement `ActionExecutor` / `Notifier`, so their dependencies stay out of the core.
