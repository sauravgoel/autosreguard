package io.autosreguard.capacity;

import io.autosreguard.core.Preconditions;

import java.util.Objects;

/**
 * A model-serving replica that needs to be placed on a GPU node.
 *
 * @param id               unique replica id
 * @param replicaGroup     replicas of the same model share a group; the packer never puts two
 *                         members of a group on one node, so a single node failure cannot take
 *                         out multiple replicas of the same model (anti-affinity)
 * @param vramGb           device memory needed: weights + KV-cache reservation + activations
 */
public record Workload(String id, String replicaGroup, double vramGb) {

    public Workload {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(replicaGroup, "replicaGroup");
        Preconditions.checkPositive(vramGb, "vramGb");
    }
}
