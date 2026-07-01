package io.autosreguard.forecast;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoltWintersForecasterTest {

    private static final Instant T0 = Instant.parse("2026-04-14T00:00:00Z");
    private static final Duration HOUR = Duration.ofHours(1);

    /** A clean diurnal shape: 1000 +/- 400 with a peak at 14:00, plus slow growth. */
    private static double demand(int hour) {
        return 1000 + 400 * Math.cos(2 * Math.PI * ((hour % 24) - 14) / 24.0) + hour * 0.5;
    }

    private static HoltWintersForecaster trained(int hours) {
        HoltWintersForecaster f = new HoltWintersForecaster(0.3, 0.02, 0.2, 24, HOUR);
        for (int h = 0; h < hours; h++) {
            f.observe(T0.plus(HOUR.multipliedBy(h)), demand(h));
        }
        return f;
    }

    @Test
    void needsTwoFullSeasonsBeforeItWillForecast() {
        assertFalse(trained(47).isReady());
        assertTrue(trained(48).isReady());
    }

    @Test
    void refusesToForecastWhileWarmingUp() {
        HoltWintersForecaster f = trained(10);
        assertThrows(IllegalStateException.class, () -> f.forecast(T0.plus(HOUR.multipliedBy(11))));
    }

    @Test
    void learnsTheDailyShape() {
        HoltWintersForecaster f = trained(24 * 5);
        int target = 24 * 5 + 14; // tomorrow's 14:00 peak
        Forecast fc = f.forecast(T0.plus(HOUR.multipliedBy(target)));
        assertEquals(demand(target), fc.point(), demand(target) * 0.05);
    }

    @Test
    void predictionIntervalBracketsThePointAndWidensWithHorizon() {
        HoltWintersForecaster f = trained(24 * 5);
        Instant last = T0.plus(HOUR.multipliedBy(24 * 5 - 1));
        Forecast near = f.forecast(last.plus(HOUR));
        Forecast far = f.forecast(last.plus(HOUR.multipliedBy(12)));
        assertTrue(near.lower() <= near.point() && near.point() <= near.upper());
        assertTrue(far.upper() - far.lower() >= near.upper() - near.lower());
    }

    @Test
    void dailyFactoryDerivesSeasonLengthFromStep() {
        HoltWintersForecaster f = HoltWintersForecaster.daily(Duration.ofMinutes(5));
        assertEquals(Duration.ofMinutes(5), f.step());
    }
}
