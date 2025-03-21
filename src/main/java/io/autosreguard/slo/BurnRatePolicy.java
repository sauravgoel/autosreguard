package io.autosreguard.slo;

import io.autosreguard.core.Preconditions;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Multi-window, multi-burn-rate alerting policy.
 *
 * <h2>Burn rate in one paragraph</h2>
 * Burn rate is how fast you are spending error budget relative to plan. A burn rate of 1
 * means you will exactly exhaust the budget at the end of the SLO period. A burn rate of
 * 14.4 sustained for one hour consumes 2% of a 30-day budget, which is worth a page.
 *
 * <h2>Why two windows per rule?</h2>
 * The long window gives significance (it is not a blip); the short window gives recency (it
 * is still happening). Requiring both makes alerts fire fast and, crucially, reset fast once
 * the problem is fixed, which matters when autonomous remediation fixes things in seconds.
 *
 * <h2>Minimum volume</h2>
 * Per-tenant SLOs create a statistics problem: a small tenant with 3 requests and 1 failure
 * has a 33% error rate. {@link #minEvents()} suppresses alerts until a window has enough
 * events to be meaningful. Tune it per deployment.
 *
 * @param rules     alert rules, evaluated highest-severity first
 * @param minEvents minimum events in the long window before a rule may fire
 */
public record BurnRatePolicy(List<Rule> rules, long minEvents) {

    public BurnRatePolicy {
        Objects.requireNonNull(rules, "rules");
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("at least one rule is required");
        }
        rules = rules.stream()
                .sorted(Comparator.comparing(Rule::severity).reversed()
                        .thenComparing(Comparator.comparingDouble(Rule::threshold).reversed()))
                .toList();
    }

    /**
     * Defaults adapted from the multi-window burn-rate approach popularised by the Google SRE
     * Workbook: two paging rules (fast and slow burn) and one ticketing rule.
     */
    public static BurnRatePolicy defaults() {
        return new BurnRatePolicy(List.of(
                new Rule(AlertSeverity.PAGE, 14.4, Duration.ofHours(1), Duration.ofMinutes(5)),
                new Rule(AlertSeverity.PAGE, 6.0, Duration.ofHours(6), Duration.ofMinutes(30)),
                new Rule(AlertSeverity.TICKET, 3.0, Duration.ofHours(24), Duration.ofHours(2))),
                100);
    }

    /** The longest window any rule looks at; counters must retain at least this much history. */
    public Duration longestWindow() {
        return rules.stream().map(Rule::longWindow).max(Comparator.naturalOrder()).orElseThrow();
    }

    /**
     * One alerting rule.
     *
     * @param severity    what to do when it fires
     * @param threshold   burn-rate multiple that must be exceeded in BOTH windows
     * @param longWindow  significance window
     * @param shortWindow recency window, typically 1/12 of the long window
     */
    public record Rule(AlertSeverity severity, double threshold, Duration longWindow, Duration shortWindow) {

        public Rule {
            Objects.requireNonNull(severity, "severity");
            Preconditions.checkPositive(threshold, "threshold");
            if (shortWindow.compareTo(longWindow) >= 0) {
                throw new IllegalArgumentException("shortWindow must be shorter than longWindow");
            }
            if (severity == AlertSeverity.NONE) {
                throw new IllegalArgumentException("a rule cannot have severity NONE");
            }
        }

        public String describe() {
            return "burn>" + threshold + "x over " + longWindow.toMinutes() + "m & " + shortWindow.toMinutes() + "m";
        }
    }
}
