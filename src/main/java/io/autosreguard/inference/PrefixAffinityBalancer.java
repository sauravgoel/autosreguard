package io.autosreguard.inference;

import io.autosreguard.core.Preconditions;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Chooses a replica for a request so that requests sharing a prompt prefix land on the same
 * replica, maximizing KV/prefix-cache reuse, without letting any replica become a hotspot.
 *
 * <h2>Algorithm: consistent hashing with bounded loads</h2>
 * Replicas are placed on a hash ring with virtual nodes. A request's {@code prefixKey} hashes to
 * a ring position; we walk clockwise to the first replica whose in-flight load is below
 * {@code ceil(c * (totalLoad + 1) / replicas)}. With {@code c = 1.25}, no replica exceeds 125%
 * of average load, yet most requests still hit their "home" replica and its warm cache.
 *
 * <p>Plain consistent hashing would send a viral system prompt's entire traffic to one GPU.
 * Plain least-loaded would destroy cache locality. Bounded loads gets most of both.
 *
 * <p>Callers must pair {@link #acquire} with {@link #release}.
 */
public final class PrefixAffinityBalancer {

    private final NavigableMap<Long, String> ring = new TreeMap<>();
    private final Map<String, Integer> load = new HashMap<>();
    private final double loadFactor;
    private final int virtualNodes;
    private int totalLoad;

    /**
     * @param replicas     initial replica ids
     * @param loadFactor   the {@code c} in bounded loads; must be > 1
     * @param virtualNodes ring points per replica; ~100 gives good balance
     */
    public PrefixAffinityBalancer(List<String> replicas, double loadFactor, int virtualNodes) {
        if (!(loadFactor > 1.0)) {
            throw new IllegalArgumentException("loadFactor must be > 1");
        }
        this.loadFactor = loadFactor;
        this.virtualNodes = Preconditions.checkPositive(virtualNodes, "virtualNodes");
        replicas.forEach(this::addReplica);
    }

    public synchronized void addReplica(String id) {
        Objects.requireNonNull(id, "id");
        if (load.containsKey(id)) {
            return;
        }
        load.put(id, 0);
        for (int i = 0; i < virtualNodes; i++) {
            ring.put(hash(id + "#" + i), id);
        }
    }

    /**
     * Removes a replica (e.g. node drained). In-flight requests on it are forgotten; callers
     * releasing them later is a harmless no-op.
     */
    public synchronized void removeReplica(String id) {
        Integer l = load.remove(id);
        if (l == null) {
            return;
        }
        totalLoad -= l;
        ring.values().removeIf(id::equals);
    }

    /** Picks a replica and increments its load. */
    public synchronized String acquire(String prefixKey) {
        if (load.isEmpty()) {
            throw new IllegalStateException("no replicas available");
        }
        String chosen;
        if (prefixKey == null || prefixKey.isEmpty()) {
            chosen = leastLoaded(); // no cache benefit to chase
        } else {
            chosen = walkRing(prefixKey);
        }
        load.merge(chosen, 1, Integer::sum);
        totalLoad++;
        return chosen;
    }

    public synchronized void release(String replica) {
        Integer l = load.get(replica);
        if (l != null && l > 0) {
            load.put(replica, l - 1);
            totalLoad--;
        }
    }

    /** The replica a prefix maps to when there is no load pressure (its cache "home"). */
    public synchronized String home(String prefixKey) {
        Map.Entry<Long, String> e = ring.ceilingEntry(hash(prefixKey));
        return (e == null ? ring.firstEntry() : e).getValue();
    }

    public synchronized int loadOf(String replica) {
        return load.getOrDefault(replica, 0);
    }

    private String walkRing(String key) {
        int cap = (int) Math.ceil(loadFactor * (totalLoad + 1) / load.size());
        long h = hash(key);
        // Walk at most one full lap: tail map from h, then wrap to the head.
        for (String r : ring.tailMap(h, true).values()) {
            if (load.get(r) < cap) {
                return r;
            }
        }
        for (String r : ring.headMap(h, false).values()) {
            if (load.get(r) < cap) {
                return r;
            }
        }
        return leastLoaded(); // unreachable by the bounded-loads guarantee, kept for safety
    }

    private String leastLoaded() {
        String best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : load.entrySet()) {
            if (e.getValue() < bestLoad || (e.getValue() == bestLoad && e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
                bestLoad = e.getValue();
            }
        }
        return best;
    }

    /** 64-bit FNV-1a followed by a MurmurHash3 finalizer for good avalanche on short keys. */
    static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b;
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
