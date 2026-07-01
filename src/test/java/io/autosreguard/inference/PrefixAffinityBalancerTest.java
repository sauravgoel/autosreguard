package io.autosreguard.inference;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrefixAffinityBalancerTest {

    private static List<String> replicas(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add("r" + i);
        }
        return out;
    }

    @Test
    void samePrefixGoesHomeWhenThereIsNoLoad() {
        PrefixAffinityBalancer b = new PrefixAffinityBalancer(replicas(8), 1.25, 100);
        String home = b.home("system-prompt-v3");
        for (int i = 0; i < 5; i++) {
            String r = b.acquire("system-prompt-v3");
            assertEquals(home, r);
            b.release(r);
        }
    }

    @Test
    void boundedLoadsSpillAHotPrefixInsteadOfMeltingOneReplica() {
        PrefixAffinityBalancer b = new PrefixAffinityBalancer(replicas(4), 1.25, 100);
        Set<String> used = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            used.add(b.acquire("viral-prompt"));
        }
        assertTrue(used.size() > 1);
        for (String r : replicas(4)) {
            assertTrue(b.loadOf(r) <= Math.ceil(1.25 * 40 / 4), r + " exceeded its bound");
        }
    }

    @Test
    void removingAReplicaOnlyMovesItsOwnPrefixes() {
        PrefixAffinityBalancer b = new PrefixAffinityBalancer(replicas(8), 1.25, 100);
        String victim = b.home("p-0");
        List<String> before = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            before.add(b.home("p-" + i));
        }
        b.removeReplica(victim);
        int moved = 0;
        for (int i = 0; i < 200; i++) {
            String now = b.home("p-" + i);
            assertNotEquals(victim, now);
            if (!now.equals(before.get(i))) {
                moved++;
                assertEquals(victim, before.get(i), "only the removed replica's keys may move");
            }
        }
        assertTrue(moved > 0);
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new PrefixAffinityBalancer(replicas(2), 1.0, 10));
        PrefixAffinityBalancer empty = new PrefixAffinityBalancer(List.of(), 1.25, 10);
        assertThrows(IllegalStateException.class, () -> empty.acquire("x"));
    }
}
