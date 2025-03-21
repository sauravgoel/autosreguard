package io.autosreguard.slo;

import io.autosreguard.core.Segment;
import io.autosreguard.core.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * The atomic unit of reliability telemetry: what happened to one inference request.
 *
 * <p>LLM latency is multi-dimensional, so we record both time-to-first-token (what a
 * streaming chat user feels) and time-per-output-token (what governs total generation time
 * for batch/summarization workloads). A single "latency" number would blur the two.
 *
 * @param timestamp    when the request completed
 * @param tenant       owning tenant (used for per-tenant SLOs)
 * @param segment      user segment of the tenant
 * @param model        model variant that actually served the request (after routing/degradation)
 * @param region       serving region
 * @param success      {@code false} for errors, timeouts and shed requests
 * @param ttftMillis   time to first token; {@code -1} if no token was produced
 * @param tpotMillis   mean time per output token; {@code -1} if not applicable
 * @param outputTokens tokens generated
 */
public record InferenceOutcome(
        Instant timestamp,
        TenantId tenant,
        Segment segment,
        String model,
        String region,
        boolean success,
        double ttftMillis,
        double tpotMillis,
        int outputTokens) {

    public InferenceOutcome {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(segment, "segment");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(region, "region");
        if (outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens must be >= 0");
        }
    }

    /** Convenience factory for a failed request (no tokens produced). */
    public static InferenceOutcome failure(Instant ts, TenantId tenant, Segment segment, String model, String region) {
        return new InferenceOutcome(ts, tenant, segment, model, region, false, -1, -1, 0);
    }

    /** Convenience factory for a successful request. */
    public static InferenceOutcome success(Instant ts, TenantId tenant, Segment segment, String model, String region,
                                           double ttftMillis, double tpotMillis, int outputTokens) {
        return new InferenceOutcome(ts, tenant, segment, model, region, true, ttftMillis, tpotMillis, outputTokens);
    }
}
