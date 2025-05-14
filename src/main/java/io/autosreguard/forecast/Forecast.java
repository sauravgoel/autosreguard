package io.autosreguard.forecast;

import java.time.Instant;

/**
 * A demand prediction with an uncertainty band.
 *
 * <p>Capacity planning should provision to {@link #upper()}, not {@link #point()}. Being
 * slightly over-provisioned costs money; being under-provisioned for the 30 seconds it takes
 * new replicas to warm up costs millions of degraded requests at planetary scale.
 *
 * @param target when the prediction is for
 * @param point  best estimate
 * @param lower  lower bound of the prediction interval (clamped at 0)
 * @param upper  upper bound of the prediction interval
 */
public record Forecast(Instant target, double point, double lower, double upper) {

    public Forecast {
        if (Double.isNaN(point) || Double.isNaN(lower) || Double.isNaN(upper)) {
            throw new IllegalArgumentException("forecast values must not be NaN");
        }
        if (lower > point || point > upper) {
            throw new IllegalArgumentException("expected lower <= point <= upper but got "
                    + lower + " / " + point + " / " + upper);
        }
    }

    /** Scales all three values, e.g. to apply an event multiplier. */
    public Forecast scaled(double factor) {
        return new Forecast(target, point * factor, lower * factor, upper * factor);
    }
}
