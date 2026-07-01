package io.autosreguard.slo;

import io.autosreguard.core.Clocks;
import io.autosreguard.core.Segment;
import io.autosreguard.core.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentedSloTrackerTest {

    private Clocks.ManualClock clock;
    private final TenantId acme = TenantId.of("acme-corp");

    @BeforeEach
    void setUp() {
        clock = Clocks.manual(Instant.parse("2026-04-17T09:00:00Z"));
    }

    private SegmentedSloTracker tracker(double target) {
        return new SegmentedSloTracker(List.of(SloDefinition.availability("ent", Segment.ENTERPRISE, target)),
                BurnRatePolicy.defaults(), Duration.ofMinutes(1), clock);
    }

    /** {@code tenants} tenants x 100 req/min for an hour; acme (tenant 0) optionally fails 4%. */
    private void simulate(SegmentedSloTracker tracker, int tenants, boolean acmeBroken) {
        for (int minute = 0; minute < 60; minute++) {
            Instant ts = clock.instant();
            for (int t = 0; t < tenants; t++) {
                TenantId tenant = t == 0 ? acme : TenantId.of("tenant-" + t);
                for (int r = 0; r < 100; r++) {
                    boolean fail = acmeBroken && t == 0 && r % 25 == 0;
                    tracker.record(fail
                            ? InferenceOutcome.failure(ts, tenant, Segment.ENTERPRISE, "m", "us")
                            : InferenceOutcome.success(ts, tenant, Segment.ENTERPRISE, "m", "us", 300, 20, 100));
                }
            }
            clock.advance(Duration.ofMinutes(1));
        }
    }

    private static SloStatus segmentStatus(List<SloStatus> statuses) {
        return statuses.stream().filter(s -> s.scope() instanceof Scope.SegmentScope).findFirst().orElseThrow();
    }

    @Test
    void healthyFleetFiresNothing() {
        SegmentedSloTracker tracker = tracker(0.9995);
        simulate(tracker, 50, false);
        assertTrue(tracker.firing().isEmpty());
    }

    @Test
    void failingTenantPagesWithFarHigherBurnThanItsSegment() {
        SegmentedSloTracker tracker = tracker(0.9995);
        simulate(tracker, 50, true);
        List<SloStatus> statuses = tracker.evaluate();
        SloStatus tenant = statuses.stream()
                .filter(s -> s.scope().label().equals("tenant=acme-corp")).findFirst().orElseThrow();

        assertEquals(AlertSeverity.PAGE, tenant.severity());
        assertTrue(tenant.longBurnRate() > 14.4);
        assertTrue(segmentStatus(statuses).longBurnRate() < tenant.longBurnRate() / 10);
    }

    @Test
    void tenantAlertIsMarkedMaskedWhenSegmentIsGreen() {
        // 100 tenants: acme's 4% becomes 0.04% of the segment, inside a 0.05% budget.
        SegmentedSloTracker tracker = tracker(0.9995);
        simulate(tracker, 100, true);
        SloStatus tenant = tracker.firing().stream()
                .filter(s -> s.scope().label().equals("tenant=acme-corp")).findFirst().orElseThrow();
        assertTrue(tenant.maskedByAggregate(), "the whole point: green segment, paging tenant");
        assertFalse(segmentStatus(tracker.evaluate()).firing());
    }

    @Test
    void tracksOneSeriesPerTenant() {
        SegmentedSloTracker tracker = tracker(0.9995);
        simulate(tracker, 50, false);
        assertEquals(50, tracker.trackedTenantSeries());
    }
}
