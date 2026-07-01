package io.autosreguard.capacity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinPackerTest {

    private final BinPacker packer = new BinPacker(0.8, 0.10);

    @Test
    void neverPlacesTwoReplicasOfTheSameGroupOnOneNode() {
        BinPacker.PackingResult r = packer.pack(
                List.of(new Workload("a-0", "a", 10), new Workload("a-1", "a", 10)),
                List.of(new NodeCapacity("n1", 80, 1.0), new NodeCapacity("n2", 80, 1.0)));
        assertNotEquals(r.placements().get("a-0"), r.placements().get("a-1"));
        assertTrue(r.unplaced().isEmpty());
    }

    @Test
    void skipsUnhealthyNodes() {
        BinPacker.PackingResult r = packer.pack(List.of(new Workload("w", "g", 10)),
                List.of(new NodeCapacity("sick", 80, 0.5), new NodeCapacity("ok", 80, 0.95)));
        assertEquals("ok", r.placements().get("w"));
    }

    @Test
    void honoursVramHeadroom() {
        // 80 GB * (1 - 10%) = 72 GB usable: a 75 GB workload must not fit.
        BinPacker.PackingResult r = packer.pack(List.of(new Workload("big", "g", 75)),
                List.of(new NodeCapacity("n1", 80, 1.0)));
        assertEquals(1, r.unplaced().size());
        assertFalse(r.placements().containsKey("big"));
    }

    @Test
    void bestFitDecreasingFillsNodesTightly() {
        BinPacker.PackingResult r = packer.pack(List.of(
                        new Workload("l-0", "large", 60),
                        new Workload("s-0", "small", 12),
                        new Workload("s2-0", "small2", 30),
                        new Workload("s3-0", "small3", 40)),
                List.of(new NodeCapacity("n1", 80, 1.0), new NodeCapacity("n2", 80, 1.0),
                        new NodeCapacity("n3", 80, 1.0)));
        assertTrue(r.unplaced().isEmpty());
        assertEquals(2, r.nodesUsed(), "60+12 and 40+30 each fit in 72 GB");
        assertEquals(r.placements().get("l-0"), r.placements().get("s-0"));
    }
}
