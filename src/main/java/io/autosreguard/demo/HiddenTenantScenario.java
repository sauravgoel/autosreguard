package io.autosreguard.demo;

import io.autosreguard.core.Clocks;
import io.autosreguard.core.Segment;
import io.autosreguard.core.TenantId;
import io.autosreguard.slo.BurnRatePolicy;
import io.autosreguard.slo.InferenceOutcome;
import io.autosreguard.slo.SegmentedSloTracker;
import io.autosreguard.slo.SloDefinition;
import io.autosreguard.slo.SloStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * "Segment, don't average": a key enterprise tenant is failing while the segment dashboard is green.
 */
final class HiddenTenantScenario implements Scenario {

    @Override
    public String name() {
        return "hidden-tenant";
    }

    @Override
    public void run(Console c) {
        c.banner("Scenario 1: The illusion of the green dashboard",
                "aggregate availability hides the customers who matter most");

        c.section("The planetary-scale math");
        long dau = 1_000_000_000L;
        double fiveNines = 0.99999;
        c.line("At %,d DAU and %.3f%% availability, %,d users are failing at any moment.",
                dau, fiveNines * 100, Math.round(dau * (1 - fiveNines)));
        c.line("If 2,000 of them belong to one enterprise, that's 20%% of the impacted population,");
        c.line("and every global dashboard is still green.");

        Clocks.ManualClock clock = Clocks.manual(Instant.parse("2026-04-17T09:00:00Z"));
        SloDefinition slo = SloDefinition.availability("enterprise-availability", Segment.ENTERPRISE, 0.9995);
        SegmentedSloTracker tracker = new SegmentedSloTracker(List.of(slo), BurnRatePolicy.defaults(),
                Duration.ofMinutes(1), clock);

        c.section("Simulating 60 minutes: 100 enterprise tenants x 100 req/min");
        TenantId acme = TenantId.of("acme-corp");
        long total = 0;
        long failed = 0;
        // Seeded so the run is reproducible; background failures land on random tenants.
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(1);
        for (int minute = 0; minute < 60; minute++) {
            Instant ts = clock.instant();
            for (int t = 0; t < 100; t++) {
                TenantId tenant = t == 0 ? acme : TenantId.of("tenant-" + t);
                for (int r = 0; r < 100; r++) {
                    // acme: 4% of requests fail (a tenant-specific quota/config bug).
                    // everyone else: ~1 in 20,000 background failures, spread randomly.
                    boolean fail = tenant.equals(acme) ? r % 25 == 0 : rnd.nextDouble() < 1.0 / 20_000;
                    tracker.record(fail
                            ? InferenceOutcome.failure(ts, tenant, Segment.ENTERPRISE, "llama-70b", "us-east")
                            : InferenceOutcome.success(ts, tenant, Segment.ENTERPRISE, "llama-70b", "us-east", 300, 25, 200));
                    total++;
                    if (fail) {
                        failed++;
                    }
                }
            }
            clock.advance(Duration.ofMinutes(1));
        }
        c.line("Segment availability: %.3f%% (target %.2f%%)", 100.0 * (total - failed) / total, slo.target() * 100);

        c.section("AutoSREGuard evaluation (firing first)");
        List<SloStatus> statuses = tracker.evaluate();
        statuses.stream().limit(3).forEach(s -> c.line("%s", s.summary()));
        SloStatus segment = statuses.stream()
                .filter(s -> s.scope() instanceof io.autosreguard.slo.Scope.SegmentScope).findFirst().orElseThrow();
        c.line("...");
        c.line("Segment status: %s", segment.summary());

        c.takeaway("Segment SLO is green; acme-corp is paging with MASKED-BY-AGGREGATE. "
                + "Route that to the account's escalation path.");
    }
}
