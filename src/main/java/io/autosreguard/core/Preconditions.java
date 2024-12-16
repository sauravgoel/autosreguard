package io.autosreguard.core;

/**
 * Tiny argument-validation helpers so every constructor fails fast with a clear message.
 * A misconfigured SLO target of {@code 99.9} instead of {@code 0.999} should blow up at
 * startup, not silently produce an error budget of -9,890%.
 */
public final class Preconditions {

    private Preconditions() {
    }

    public static double checkFraction(double v, String name) {
        if (!(v > 0.0 && v < 1.0)) {
            throw new IllegalArgumentException(name + " must be in (0,1) but was " + v);
        }
        return v;
    }

    public static double checkUnitInterval(double v, String name) {
        if (!(v >= 0.0 && v <= 1.0)) {
            throw new IllegalArgumentException(name + " must be in [0,1] but was " + v);
        }
        return v;
    }

    public static double checkPositive(double v, String name) {
        if (!(v > 0.0) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(name + " must be a positive finite number but was " + v);
        }
        return v;
    }

    public static long checkPositive(long v, String name) {
        if (v <= 0) {
            throw new IllegalArgumentException(name + " must be positive but was " + v);
        }
        return v;
    }

    public static int checkPositive(int v, String name) {
        if (v <= 0) {
            throw new IllegalArgumentException(name + " must be positive but was " + v);
        }
        return v;
    }

    public static double checkNonNegative(double v, String name) {
        if (!(v >= 0.0) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(name + " must be >= 0 and finite but was " + v);
        }
        return v;
    }
}
