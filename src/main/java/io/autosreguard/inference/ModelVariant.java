package io.autosreguard.inference;

import io.autosreguard.core.Preconditions;

import java.util.Objects;

/**
 * A servable model variant: same task, different size/precision/cost.
 *
 * <p>A family of variants (e.g. large-fp16, large-int8, small) is what makes graceful
 * degradation possible: under capacity pressure we fall back to a smaller, faster variant
 * instead of returning an error. Reduced quality beats no answer.
 *
 * @param name             unique variant name
 * @param qualityScore     relative quality 0..1 from offline evals; the router never picks below
 *                         a request's {@code minQuality}
 * @param baseTtftMs       unloaded time-to-first-token (prefill for a typical prompt)
 * @param prefillMsPerKTok extra TTFT per 1k prompt tokens
 * @param tpotMs           unloaded time per output token
 * @param maxContextTokens context window
 * @param relativeCost     cost per token relative to the flagship (flagship = 1.0)
 */
public record ModelVariant(String name, double qualityScore, double baseTtftMs, double prefillMsPerKTok,
                           double tpotMs, int maxContextTokens, double relativeCost) {

    public ModelVariant {
        Objects.requireNonNull(name, "name");
        Preconditions.checkUnitInterval(qualityScore, "qualityScore");
        Preconditions.checkPositive(baseTtftMs, "baseTtftMs");
        Preconditions.checkNonNegative(prefillMsPerKTok, "prefillMsPerKTok");
        Preconditions.checkPositive(tpotMs, "tpotMs");
        Preconditions.checkPositive(maxContextTokens, "maxContextTokens");
        Preconditions.checkPositive(relativeCost, "relativeCost");
    }
}
