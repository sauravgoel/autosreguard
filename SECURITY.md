# Security Policy

AutoSREGuard can roll back deployments, revert configuration, drain nodes and shed traffic.
Treat it like any other privileged control-plane component.

## Deployment guidance

- **Admin API**: binds to `127.0.0.1` by default. It has no built-in authentication; expose it
  only behind an authenticating proxy (mTLS or SSO) or restrict it with a network policy.
  Every mutating call requires a `by` parameter so actions are attributed in the audit log.
- **Dry-run**: `remediation.dry-run=true` is the default. Enable acting mode deliberately.
- **Executor credentials**: give your `ActionExecutor` implementation least-privilege
  credentials scoped to the resources it may change.
- **Audit log**: ship `audit.file` to append-only storage (e.g. object storage with retention
  lock) so remediation history cannot be silently edited.

## Reporting a vulnerability

Please report suspected vulnerabilities privately through
[GitHub Security Advisories](https://github.com/sauravgoel/autosreguard/security/advisories/new)
rather than opening a public issue. Include reproduction steps and the affected version. We aim to acknowledge
reports within three business days.
