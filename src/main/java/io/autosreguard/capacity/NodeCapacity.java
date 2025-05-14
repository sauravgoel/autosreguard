package io.autosreguard.capacity;

import io.autosreguard.core.Preconditions;

import java.util.Objects;

/**
 * Free accelerator memory on a node available to the packer.
 *
 * @param nodeId      node identifier
 * @param capacityGb  schedulable VRAM (after system reservation)
 * @param healthScore 0..1 from {@link io.autosreguard.gpu.GpuHealthScorer}; the packer avoids
 *                    nodes below its health floor, so work is not placed on degrading hardware
 */
public record NodeCapacity(String nodeId, double capacityGb, double healthScore) {

    public NodeCapacity {
        Objects.requireNonNull(nodeId, "nodeId");
        Preconditions.checkPositive(capacityGb, "capacityGb");
        Preconditions.checkUnitInterval(healthScore, "healthScore");
    }
}
