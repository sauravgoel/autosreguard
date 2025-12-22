package io.autosreguard.inference;

import io.autosreguard.core.Preconditions;
import io.autosreguard.core.Segment;
import io.autosreguard.core.TenantId;

import java.util.Objects;

/**
 * Routing-relevant metadata about an inference request (never the prompt itself: SRE tooling
 * has no business holding user content).
 *
 * @param requestId       unique id; also used for deterministic shedding decisions
 * @param tenant          tenant
 * @param segment         user segment (drives shedding priority and degradation eligibility)
 * @param promptTokens    input length
 * @param maxOutputTokens generation cap
 * @param latencyBudgetMs end-to-end budget for non-streaming, TTFT budget for streaming
 * @param streaming       whether the client streams tokens (then TTFT is what users feel)
 * @param prefixKey       hash of the shared prompt prefix (system prompt, few-shot block) used for
 *                        cache-affinity routing; empty if none
 * @param minQuality      lowest acceptable variant quality; 0 = any
 */
public record InferenceRequest(String requestId, TenantId tenant, Segment segment, int promptTokens,
                               int maxOutputTokens, double latencyBudgetMs, boolean streaming,
                               String prefixKey, double minQuality) {

    public InferenceRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(segment, "segment");
        Objects.requireNonNull(prefixKey, "prefixKey");
        Preconditions.checkPositive(promptTokens, "promptTokens");
        Preconditions.checkPositive(maxOutputTokens, "maxOutputTokens");
        Preconditions.checkPositive(latencyBudgetMs, "latencyBudgetMs");
        Preconditions.checkUnitInterval(minQuality, "minQuality");
    }

    public int totalTokens() {
        return promptTokens + maxOutputTokens;
    }
}
