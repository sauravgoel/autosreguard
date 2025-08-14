package io.autosreguard.diagnosis;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Directed dependency graph: an edge {@code A -> B} means "A calls B".
 *
 * <p>Diagnosis uses it to distinguish symptoms from causes: when a gateway, a router, and a
 * model pool are all anomalous and the gateway calls the router which calls the pool, the pool
 * is the likeliest root cause and the other two are symptoms.
 */
public final class ServiceGraph {

    private final Map<String, Set<String>> dependencies = new HashMap<>();

    public synchronized ServiceGraph dependsOn(String caller, String callee) {
        if (caller.equals(callee)) {
            throw new IllegalArgumentException("self-dependency: " + caller);
        }
        dependencies.computeIfAbsent(caller, k -> new HashSet<>()).add(callee);
        dependencies.computeIfAbsent(callee, k -> new HashSet<>());
        return this;
    }

    public synchronized Set<String> directDependencies(String component) {
        return Collections.unmodifiableSet(new HashSet<>(dependencies.getOrDefault(component, Set.of())));
    }

    /** All components reachable from {@code component} (transitive dependencies). */
    public synchronized Set<String> transitiveDependencies(String component) {
        Set<String> seen = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>(dependencies.getOrDefault(component, Set.of()));
        while (!stack.isEmpty()) {
            String c = stack.pop();
            if (seen.add(c)) {
                stack.addAll(dependencies.getOrDefault(c, Set.of()));
            }
        }
        return seen;
    }

    /** Components that (transitively) depend on {@code component}: its potential blast radius. */
    public synchronized Set<String> dependents(String component) {
        Set<String> out = new HashSet<>();
        for (String c : dependencies.keySet()) {
            if (!c.equals(component) && transitiveDependencies(c).contains(component)) {
                out.add(c);
            }
        }
        return out;
    }
}
