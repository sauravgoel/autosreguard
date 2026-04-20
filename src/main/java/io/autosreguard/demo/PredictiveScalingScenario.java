package io.autosreguard.demo;

import io.autosreguard.capacity.CapacityPlanner;
import io.autosreguard.capacity.GpuProfile;
import io.autosreguard.capacity.ScalingDecision;
import io.autosreguard.core.Clocks;
import io.autosreguard.forecast.EventAwareForecaster;
import io.autosreguard.forecast.EventSignal;
import io.autosreguard.forecast.HoltWintersForecaster;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static io.autosreguard.demo.BadDeployScenario.HHMMSS;

/**
 * Predictive vs reactive capacity. Replicas take 6 minutes to warm up (weights into VRAM).
 * A product launch doubles demand at 10:00. Reactive scaling pays the warm-up in degraded
 * requests; predictive scaling starts early because the forecast and event calendar said so.
 */
final class PredictiveScalingScenario implements Scenario {

    private static final Duration STEP = Duration.ofMinutes(5);
    private static final GpuProfile PROFILE = new GpuProfile("h100-llama-70b", 80, 2500, Duration.ofMinutes(6));
    private static final double TARGET_UTIL = 0.7;

    @Override
    public String name() {
        return "predictive-scaling";
    }

    /** Diurnal demand (tokens/s) peaking at 14:00 UTC, growing 2%/day, +-3% noise. */
    static double demand(Instant t, int day, SplittableRandom rnd, List<EventSignal> events) {
        double hour = t.atZone(ZoneOffset.UTC).getHour() + t.atZone(ZoneOffset.UTC).getMinute() / 60.0;
        double base = 18_000 + 12_000 * Math.sin(2 * Math.PI * (hour - 8) / 24);
        double v = base * (1 + 0.02 * day) * (1 + rnd.nextDouble(-0.03, 0.03));
        for (EventSignal e : events) {
            if (e.activeAt(t)) {
                v *= e.multiplier();
            }
        }
        return v;
    }

    /** Minimal fleet model: replicas become ready after warm-up; scale-down is immediate. */
    private static final class Fleet {
        int ready;
        final List<Instant> pending = new ArrayList<>();
        double unmetTokenSeconds;
        int underProvisionedSteps;
        long replicaSteps;

        Fleet(int ready) {
            this.ready = ready;
        }

        int total() {
            return ready + pending.size();
        }

        void promote(Instant now) {
            int before = pending.size();
            pending.removeIf(t -> !t.isAfter(now));
            ready += before - pending.size();
        }

        void apply(int desired, Instant now) {
            int total = total();
            for (int i = total; i < desired; i++) {
                pending.add(now.plus(PROFILE.warmup()));
            }
            for (int i = desired; i < total; i++) { // scale down: cancel pending first
                if (!pending.isEmpty()) {
                    pending.remove(pending.size() - 1);
                } else {
                    ready--;
                }
            }
        }

        void serve(double demand) {
            double cap = ready * PROFILE.sustainableTokensPerSec();
            if (demand > cap) {
                underProvisionedSteps++;
                unmetTokenSeconds += (demand - cap) * STEP.toSeconds();
            }
            replicaSteps += ready;
        }
    }

    @Override
    public void run(Console c) {
        c.banner("Scenario 4: Provision before the wave arrives",
                "autoscaling is table stakes; forecasting demand hides replica warm-up");

        Instant day0 = Instant.parse("2026-04-15T00:00:00Z");
        Instant day2 = day0.plus(Duration.ofDays(2));
        List<EventSignal> events = List.of(new EventSignal("product-launch",
                day2.plus(Duration.ofHours(10)), day2.plus(Duration.ofHours(12)), 1.8));

        HoltWintersForecaster hw = HoltWintersForecaster.daily(STEP);
        EventAwareForecaster forecaster = new EventAwareForecaster(hw);
        events.forEach(forecaster::schedule);

        SplittableRandom rnd = new SplittableRandom(11);
        Instant simStart = day2.plus(Duration.ofHours(8));
        c.section("Training Holt-Winters on 2 days + morning of day 3 (5-minute samples)");
        Instant t = day0;
        for (; t.isBefore(simStart); t = t.plus(STEP)) {
            int day = (int) Duration.between(day0, t).toDays();
            forecaster.observe(t, demand(t, day, rnd, events));
        }
        c.line("forecaster ready=%s; calendar: %s x%.1f from %s to %s", forecaster.isReady(),
                events.get(0).name(), events.get(0).multiplier(),
                HHMMSS.format(events.get(0).start()), HHMMSS.format(events.get(0).end()));

        Clocks.ManualClock clock = Clocks.manual(simStart);
        CapacityPlanner planner = CapacityPlanner.builder()
                .forecaster(forecaster).profile(PROFILE).clock(clock)
                .lookahead(Duration.ofMinutes(15)).sampleStep(STEP)
                .targetUtilization(TARGET_UTIL).minReplicas(2).maxReplicas(200)
                .build();

        double d0 = demand(simStart, 2, new SplittableRandom(1), events);
        int initial = (int) Math.ceil(d0 / (PROFILE.sustainableTokensPerSec() * TARGET_UTIL));
        Fleet reactive = new Fleet(initial);
        Fleet predictive = new Fleet(initial);

        c.section("Simulating 08:00-13:00 on launch day (6-minute replica warm-up)");
        c.line("%-8s %10s %9s %9s  %s", "time", "demand", "reactive", "predict", "note");
        for (t = simStart; t.isBefore(simStart.plus(Duration.ofHours(5))); t = t.plus(STEP)) {
            clock.advance(Duration.between(clock.instant(), t));
            double d = demand(t, 2, rnd, events);
            reactive.promote(t);
            predictive.promote(t);
            reactive.serve(d);
            predictive.serve(d);

            // Reactive: size for what is happening now.
            reactive.apply((int) Math.ceil(d / (PROFILE.sustainableTokensPerSec() * TARGET_UTIL)), t);
            // Predictive: size for what the forecast says will happen by the time replicas are warm.
            ScalingDecision sd = planner.plan(predictive.total(), d);
            predictive.apply(sd.desiredReplicas(), t);
            forecaster.observe(t, d);

            boolean interesting = t.atZone(ZoneOffset.UTC).getMinute() % 30 == 0
                    || (t.isAfter(events.get(0).start().minus(Duration.ofMinutes(25)))
                    && t.isBefore(events.get(0).start().plus(Duration.ofMinutes(15))));
            if (interesting) {
                double rcap = reactive.ready * PROFILE.sustainableTokensPerSec();
                double pcap = predictive.ready * PROFILE.sustainableTokensPerSec();
                c.line("%-8s %10.0f %4d%-5s %4d%-5s  %s", HHMMSS.format(t), d,
                        reactive.ready, d > rcap ? " SHORT" : "",
                        predictive.ready, d > pcap ? " SHORT" : "",
                        sd.isChange() ? sd.reason() : "");
            }
        }

        c.section("Results");
        c.line("%-11s under-provisioned intervals=%d  unmet demand=%,.0f tokens  replica-steps=%,d",
                "reactive:", reactive.underProvisionedSteps, reactive.unmetTokenSeconds, reactive.replicaSteps);
        c.line("%-11s under-provisioned intervals=%d  unmet demand=%,.0f tokens  replica-steps=%,d",
                "predictive:", predictive.underProvisionedSteps, predictive.unmetTokenSeconds, predictive.replicaSteps);
        c.takeaway("Predictive scaling had replicas warm before the launch hit; reactive scaling spent "
                + "the warm-up window dropping tokens.");
    }
}
