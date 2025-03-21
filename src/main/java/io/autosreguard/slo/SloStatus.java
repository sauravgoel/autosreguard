package io.autosreguard.slo;

/**
 * Point-in-time evaluation of one SLO for one scope.
 *
 * @param slo                  the objective evaluated
 * @param scope                segment or tenant
 * @param eventsInHorizon      valid events seen over the tracking horizon
 * @param errorRateInHorizon   bad/total over the tracking horizon
 * @param budgetRemaining      fraction of error budget left over the horizon; negative when blown
 * @param severity             highest-severity rule currently firing (or NONE)
 * @param firingRule           description of that rule, or empty
 * @param longBurnRate         burn rate in the firing rule's long window (or the fastest rule's, if none fire)
 * @param shortBurnRate        burn rate in the matching short window
 * @param maskedByAggregate    {@code true} when this is a tenant alert while its segment is NOT alerting:
 *                             the "dashboards are green but a key customer is failing" case
 */
public record SloStatus(
        SloDefinition slo,
        Scope scope,
        long eventsInHorizon,
        double errorRateInHorizon,
        double budgetRemaining,
        AlertSeverity severity,
        String firingRule,
        double longBurnRate,
        double shortBurnRate,
        boolean maskedByAggregate) {

    public boolean firing() {
        return severity != AlertSeverity.NONE;
    }

    SloStatus withMasked(boolean masked) {
        return new SloStatus(slo, scope, eventsInHorizon, errorRateInHorizon, budgetRemaining, severity,
                firingRule, longBurnRate, shortBurnRate, masked);
    }

    /** One-line summary suitable for logs and notifications. */
    public String summary() {
        return String.format(java.util.Locale.ROOT,
                "[%s] slo=%s %s err=%.4f%% budgetLeft=%.1f%% burn=%.1fx/%.1fx%s%s",
                severity, slo.name(), scope.label(), errorRateInHorizon * 100, budgetRemaining * 100,
                longBurnRate, shortBurnRate,
                firingRule.isEmpty() ? "" : " rule=\"" + firingRule + "\"",
                maskedByAggregate ? " MASKED-BY-AGGREGATE" : "");
    }
}
