package io.autosreguard.remediation;

import io.autosreguard.diagnosis.ChangeEvent;
import io.autosreguard.diagnosis.Hypothesis;

import java.util.Objects;
import java.util.Optional;

/**
 * Maps a diagnosis to a pre-validated remediation playbook.
 *
 * <p>The playbooks encode the patterns that work at scale:
 * <ul>
 *   <li>{@code BAD_CHANGE} (deploy/model) -> automated <b>rollback</b></li>
 *   <li>{@code BAD_CHANGE} (config) -> <b>configuration healing</b> (revert)</li>
 *   <li>{@code CAPACITY_EXHAUSTION} -> <b>capacity injection</b> (+50%, capped)</li>
 *   <li>{@code HARDWARE_DEGRADATION} -> <b>drain</b> the node</li>
 *   <li>{@code COMPONENT_FAULT} -> <b>traffic shedding</b> of low-priority load to protect core journeys</li>
 * </ul>
 * Anything outside these playbooks returns empty and is escalated. Automation should only do
 * what has been validated; novel situations are what humans are for.
 */
public final class RemediationPlanner {

    private final FleetState fleet;
    private final double capacityInjectionFactor;
    private final double sheddingStep;

    public RemediationPlanner(FleetState fleet, double capacityInjectionFactor, double sheddingStep) {
        this.fleet = Objects.requireNonNull(fleet, "fleet");
        if (capacityInjectionFactor <= 1.0) {
            throw new IllegalArgumentException("capacityInjectionFactor must be > 1");
        }
        if (!(sheddingStep > 0 && sheddingStep <= 1)) {
            throw new IllegalArgumentException("sheddingStep must be in (0,1]");
        }
        this.capacityInjectionFactor = capacityInjectionFactor;
        this.sheddingStep = sheddingStep;
    }

    public static RemediationPlanner defaults(FleetState fleet) {
        return new RemediationPlanner(fleet, 1.5, 0.25);
    }

    public Optional<RemediationAction> plan(Hypothesis h) {
        return switch (h.kind()) {
            case BAD_CHANGE -> h.change().map(this::undoChange);
            case CAPACITY_EXHAUSTION -> {
                int cur = fleet.replicas(h.subject());
                int max = fleet.maxReplicas(h.subject());
                int target = Math.min(max, (int) Math.ceil(Math.max(1, cur) * capacityInjectionFactor));
                yield target > cur ? Optional.of(new RemediationAction.ScaleReplicas(h.subject(), cur, target))
                        : Optional.empty(); // already at max: a human must decide what to give up
            }
            case HARDWARE_DEGRADATION -> Optional.of(new RemediationAction.DrainNode(h.subject(), true));
            case COMPONENT_FAULT -> {
                double cur = fleet.sheddingFraction(h.subject());
                double next = Math.min(1.0, cur + sheddingStep);
                yield next > cur ? Optional.of(new RemediationAction.ShedLoad(h.subject(), cur, next))
                        : Optional.empty();
            }
        };
    }

    private RemediationAction undoChange(ChangeEvent ch) {
        return switch (ch.kind()) {
            case DEPLOYMENT, MODEL_ROLLOUT -> new RemediationAction.Rollback(ch.component(), ch.toValue(), ch.fromValue());
            // For CONFIG changes, the change id is the config key (see ChangeEvent docs).
            case CONFIG -> new RemediationAction.RevertConfig(ch.component(), ch.id(), ch.toValue(), ch.fromValue());
        };
    }
}
