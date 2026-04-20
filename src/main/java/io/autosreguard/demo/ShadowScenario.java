package io.autosreguard.demo;

import io.autosreguard.remediation.AutonomyTier;
import io.autosreguard.remediation.BlastRadius;
import io.autosreguard.remediation.ConfidencePolicy;
import io.autosreguard.shadow.ShadowEvaluator;

import java.time.Duration;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Before a new decision policy gets control, run it in shadow mode: it sees the same inputs as
 * the incumbent, its outputs are recorded but never acted on, and agreement is measured.
 *
 * <p>Here the thing being shadowed is AutoSREGuard's own autonomy policy. Someone proposes
 * lowering the auto-remediation threshold to cut pages. Before it gets control, it runs in
 * shadow against the incumbent on every decision; divergences are exactly the incidents where
 * the new policy would have acted without a human.
 */
final class ShadowScenario implements Scenario {

    /** One remediation decision point: diagnosis confidence and the action's blast radius. */
    record Case(double confidence, BlastRadius radius) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "(conf=%.2f, %s)", confidence, radius);
        }
    }

    @Override
    public String name() {
        return "shadow";
    }

    @Override
    public void run(Console c) {
        c.banner("Scenario 7: Shadow mode before control authority",
                "a new policy earns autonomy by agreeing with the old one, in production, without acting");

        ConfidencePolicy incumbent = ConfidencePolicy.defaults();
        Duration veto = incumbent.vetoWindow();

        c.section("Candidate A: aggressive (auto >= 0.75, suggest >= 0.50)");
        report(c, run(incumbent, new ConfidencePolicy(0.75, 0.50, veto), "aggressive"));

        c.section("Candidate B: minor retune (auto >= 0.84, suggest >= 0.60)");
        report(c, run(incumbent, new ConfidencePolicy(0.84, 0.60, veto), "retune"));

        c.takeaway("Shadow evaluation turns 'I think this threshold is fine' into evidence: how often, and on "
                + "which incidents, the candidate would have acted differently. Passing unlocks review, not control.");
    }

    private static ShadowEvaluator<Case, AutonomyTier> run(ConfidencePolicy incumbent, ConfidencePolicy candidate,
                                                           String name) {
        ShadowEvaluator<Case, AutonomyTier> eval = new ShadowEvaluator<>(name,
                k -> incumbent.tierFor(k.confidence(), k.radius()),
                k -> candidate.tierFor(k.confidence(), k.radius()),
                AutonomyTier::equals, 3);
        SplittableRandom rnd = new SplittableRandom(99);
        BlastRadius[] radii = BlastRadius.values();
        for (int i = 0; i < 2_000; i++) {
            eval.evaluate(new Case(rnd.nextDouble(), radii[rnd.nextInt(radii.length)]));
        }
        return eval;
    }

    private static void report(Console c, ShadowEvaluator<Case, AutonomyTier> eval) {
        ShadowEvaluator.Report r = eval.report();
        c.line("%s", r.summary());
        r.divergences().forEach(d -> c.line("  divergence: %s", d));
        boolean ready = eval.readyForReview(1_000, 0.98, 0.0);
        c.line("gate (n>=1000, agreement>=98%%, zero candidate errors): %s",
                ready ? "PASS -> ready for human review" : "FAIL -> stays in shadow");
    }
}
