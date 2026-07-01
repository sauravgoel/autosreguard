package io.autosreguard.forecast;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventAwareForecasterTest {

    private static final Instant T0 = Instant.parse("2026-04-17T10:00:00Z");

    /** Records what it was fed and always forecasts a flat 100. */
    private static final class Flat implements DemandForecaster {
        final List<Double> observed = new ArrayList<>();

        @Override
        public void observe(Instant at, double value) {
            observed.add(value);
        }

        @Override
        public Forecast forecast(Instant target) {
            return new Forecast(target, 100, 90, 110);
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }

    @Test
    void scalesForecastsDuringScheduledEvents() {
        EventAwareForecaster f = new EventAwareForecaster(new Flat());
        f.schedule(new EventSignal("launch", T0, T0.plus(Duration.ofHours(2)), 1.8));

        assertEquals(100, f.forecast(T0.minusSeconds(60)).point(), 1e-9);
        Forecast during = f.forecast(T0.plusSeconds(60));
        assertEquals(180, during.point(), 1e-9);
        assertEquals(198, during.upper(), 1e-9);
        assertEquals(List.of("launch"), f.activeEventNames(T0.plusSeconds(60)));
    }

    @Test
    void overlappingEventsCompound() {
        EventAwareForecaster f = new EventAwareForecaster(new Flat());
        f.schedule(new EventSignal("launch", T0, T0.plus(Duration.ofHours(2)), 2.0));
        f.schedule(new EventSignal("sports-final", T0, T0.plus(Duration.ofHours(1)), 1.5));
        assertEquals(3.0, f.multiplierAt(T0.plusSeconds(1)), 1e-9);
    }

    @Test
    void deScalesObservationsSoTheBaselineIsNotPollutedByTheEvent() {
        Flat inner = new Flat();
        EventAwareForecaster f = new EventAwareForecaster(inner);
        f.schedule(new EventSignal("launch", T0, T0.plus(Duration.ofHours(2)), 2.0));
        f.observe(T0.plusSeconds(60), 400);
        assertEquals(200, inner.observed.get(0), 1e-9);
    }

    @Test
    void pruneDropsFinishedEvents() {
        EventAwareForecaster f = new EventAwareForecaster(new Flat());
        f.schedule(new EventSignal("old", T0, T0.plusSeconds(60), 2.0));
        f.pruneBefore(T0.plusSeconds(3600));
        assertEquals(1.0, f.multiplierAt(T0.plusSeconds(30)), 1e-9);
    }

    @Test
    void rejectsInvertedEventWindows() {
        assertThrows(IllegalArgumentException.class, () -> new EventSignal("x", T0, T0, 2.0));
        assertTrue(new EventSignal("ok", T0, T0.plusSeconds(1), 2.0).multiplier() > 1);
    }
}
