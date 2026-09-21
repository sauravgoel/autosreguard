# Contributing to AutoSREGuard

Thanks for helping make AI inference more reliable. AutoSREGuard is operational software that
can change production, so the bar for changes is deliberately high.

## Ground rules

1. **No new runtime dependencies** without an ADR (see `docs/adr/0001`). Test-scoped
   dependencies are fine.
2. **Every automated action must be reversible.** New `RemediationAction` types must implement
   `inverse()` so that `a.inverse().inverse().equals(a)`, and must declare a `BlastRadius`.
3. **Safe by default.** New behaviour that acts on infrastructure ships disabled or in dry-run.
4. **Explain decisions.** Anything that produces a decision (hypotheses, verdicts, routing)
   carries human-readable evidence or reasons. Operators must be able to answer "why?".
5. **Deterministic tests.** Inject `java.time.Clock` (use `Clocks.manual`) and seeded
   randomness. No `Thread.sleep` in tests.

## Reporting issues

Open an issue at https://github.com/sauravgoel/autosreguard/issues with the version, your
configuration (secrets removed) and the relevant audit-log lines.

## Workflow

```bash
mvn -B verify                                   # compile with -Xlint:all, run tests
java -jar target/autosreguard-*.jar --scenario=all  # scenarios double as end-to-end checks
```

If you change tuning (thresholds, confidence formulas), run the scenarios and update
`ScenarioSmokeTest` and the README if the narrative changes. A scenario whose output no
longer matches its takeaway is a failing test, not a cosmetic issue.

## Style

- Java 21, records and sealed types where they model the domain.
- Javadoc on every public type explaining *why*, not just *what*.
- Package-private by default; public only for integration seams.

## Commit messages

Use the imperative mood and reference the relevant ADR when applicable, e.g.
`remediation: require +0.07 confidence for HIGH blast radius (ADR-0002)`.
