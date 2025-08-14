package io.autosreguard.detection;

/**
 * Canonical signal names. Diagnosis reasons about signal <em>families</em> (hardware,
 * saturation, errors), so naming discipline matters: a GPU metric that is not prefixed
 * {@code gpu_} will not be considered as hardware evidence.
 */
public final class Signals {

    private Signals() {
    }

    public static final String ERROR_RATE = "error_rate";
    public static final String TTFT_P99_MS = "ttft_p99_ms";
    public static final String TPOT_P99_MS = "tpot_p99_ms";
    public static final String QUEUE_DEPTH = "queue_depth";
    public static final String UTILIZATION = "utilization";
    public static final String GPU_ECC_ERRORS = "gpu_ecc_errors";
    public static final String GPU_TEMP_C = "gpu_temp_c";
    public static final String GPU_THROTTLE = "gpu_throttle_ratio";

    /** Accelerator-level signals indicating hardware degradation. */
    public static boolean isHardware(String signal) {
        return signal.startsWith("gpu_");
    }

    /** Signals indicating demand is exceeding capacity. */
    public static boolean isSaturation(String signal) {
        return signal.equals(QUEUE_DEPTH) || signal.equals(UTILIZATION);
    }
}
