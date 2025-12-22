package io.autosreguard.shadow;

import io.autosreguard.core.Preconditions;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Runs a candidate decision policy alongside the incumbent without acting on its output.
 *
 * <p>Shadow mode is how a model earns control authority. Before an ML forecaster drives
 * scaling or a new diagnosis model drives remediation, it runs here for days, and we measure:
 * how often it agrees with the incumbent, how often it errors, and concrete examples of where
 * it diverges, which a human reviews before promotion.
 *
 * <h2>Isolation guarantees</h2>
 * <ul>
 *   <li>The incumbent's result is always what's returned. Always.</li>
 *   <li>Candidate exceptions are caught and counted; they never propagate.</li>
 *   <li>The candidate runs after the incumbent, so a slow candidate cannot delay the
 *       incumbent's decision. In latency-sensitive paths, run {@link #evaluate} off-thread.</li>
 * </ul>
 *
 * @param <I> decision input type
 * @param <D> decision type
 */
public final class ShadowEvaluator<I, D> {

    private final String name;
    private final Function<I, D> incumbent;
    private final Function<I, D> candidate;
    private final BiPredicate<D, D> agrees;
    private final int maxDivergenceSamples;

    private long total;
    private long agreements;
    private long candidateErrors;
    private final Deque<String> divergences = new ArrayDeque<>();

    /**
     * @param name                 label for reports
     * @param incumbent            the policy currently in control
     * @param candidate            the policy under evaluation
     * @param agrees               equivalence test; e.g. "replica counts within 10%" rather than strict equality
     * @param maxDivergenceSamples how many divergence examples to retain for review
     */
    public ShadowEvaluator(String name, Function<I, D> incumbent, Function<I, D> candidate,
                           BiPredicate<D, D> agrees, int maxDivergenceSamples) {
        this.name = Objects.requireNonNull(name, "name");
        this.incumbent = Objects.requireNonNull(incumbent, "incumbent");
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.agrees = Objects.requireNonNull(agrees, "agrees");
        this.maxDivergenceSamples = Preconditions.checkPositive(maxDivergenceSamples, "maxDivergenceSamples");
    }

    /** Computes the incumbent's decision (returned) and the candidate's (recorded only). */
    public D evaluate(I input) {
        D chosen = incumbent.apply(input);
        D shadow;
        try {
            shadow = candidate.apply(input);
        } catch (RuntimeException e) {
            synchronized (this) {
                total++;
                candidateErrors++;
                remember("candidate threw " + e.getClass().getSimpleName() + " on input " + input);
            }
            return chosen;
        }
        synchronized (this) {
            total++;
            if (agrees.test(chosen, shadow)) {
                agreements++;
            } else {
                remember("input=" + input + " incumbent=" + chosen + " candidate=" + shadow);
            }
        }
        return chosen;
    }

    private void remember(String s) {
        divergences.addLast(s);
        if (divergences.size() > maxDivergenceSamples) {
            divergences.removeFirst();
        }
    }

    public synchronized Report report() {
        return new Report(name, total, agreements, candidateErrors, List.copyOf(divergences));
    }

    /**
     * Promotion gate: enough samples, high agreement, and effectively zero candidate errors.
     * Passing it means "ready for human review", not "promote automatically".
     */
    public synchronized boolean readyForReview(long minSamples, double minAgreement, double maxErrorRate) {
        if (total < minSamples) {
            return false;
        }
        Report r = report();
        return r.agreementRate() >= minAgreement && r.candidateErrorRate() <= maxErrorRate;
    }

    /**
     * @param name            evaluator name
     * @param total           decisions evaluated
     * @param agreements      decisions where candidate agreed
     * @param candidateErrors candidate exceptions
     * @param divergences     most recent divergence examples
     */
    public record Report(String name, long total, long agreements, long candidateErrors, List<String> divergences) {
        public double agreementRate() {
            return total == 0 ? 0 : (double) agreements / total;
        }

        public double candidateErrorRate() {
            return total == 0 ? 0 : (double) candidateErrors / total;
        }

        public String summary() {
            return String.format(Locale.ROOT, "shadow[%s] n=%d agreement=%.1f%% candidateErrors=%d",
                    name, total, agreementRate() * 100, candidateErrors);
        }
    }
}
