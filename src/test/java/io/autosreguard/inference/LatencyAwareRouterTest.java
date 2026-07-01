package io.autosreguard.inference;

import io.autosreguard.core.Clocks;
import io.autosreguard.core.Segment;
import io.autosreguard.core.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyAwareRouterTest {

    private static final ModelVariant FP16 = new ModelVariant("fp16", 0.92, 350, 120, 28, 32_768, 1.0);
    private static final ModelVariant INT8 = new ModelVariant("int8", 0.90, 260, 90, 18, 32_768, 0.55);
    private static final ModelVariant SMALL = new ModelVariant("small", 0.78, 90, 30, 7, 8_192, 0.12);

    private Map<String, CircuitBreaker> breakers;
    private LiveLatencyModel latency;
    private LoadShedder shedder;
    private LatencyAwareRouter router;

    @BeforeEach
    void setUp() {
        Clocks.ManualClock clock = Clocks.manual(Instant.parse("2026-04-17T18:00:00Z"));
        breakers = Map.of("fp16", CircuitBreaker.defaults("fp16", clock),
                "int8", CircuitBreaker.defaults("int8", clock),
                "small", CircuitBreaker.defaults("small", clock));
        latency = new LiveLatencyModel(0.2);
        shedder = new LoadShedder();
        router = new LatencyAwareRouter(List.of(SMALL, FP16, INT8), breakers, latency, shedder, 0.7);
    }

    private static InferenceRequest req(Segment s, double minQuality, int prompt) {
        return new InferenceRequest("r-" + s, TenantId.of("t"), s, prompt, 200, 2_500, true, "", minQuality);
    }

    private static RoutingDecision.Routed routed(RoutingDecision d) {
        return assertInstanceOf(RoutingDecision.Routed.class, d);
    }

    @Test
    void ladderIsOrderedByQuality() {
        assertEquals(List.of(FP16, INT8, SMALL), router.ladder());
    }

    @Test
    void unloadedPoolServesEveryoneTheFlagship() {
        for (Segment s : Segment.values()) {
            RoutingDecision.Routed r = routed(router.route(req(s, 0, 1000)));
            assertEquals(FP16, r.variant());
            assertFalse(r.degraded());
        }
    }

    @Test
    void underPressureConsumerDegradesWhileEnterpriseKeepsTheFlagship() {
        latency.setPressure("fp16", 0.75);
        RoutingDecision.Routed consumer = routed(router.route(req(Segment.CONSUMER, 0, 1000)));
        RoutingDecision.Routed enterprise = routed(router.route(req(Segment.ENTERPRISE, 0.85, 1000)));
        assertEquals(INT8, consumer.variant());
        assertTrue(consumer.degraded());
        assertEquals(FP16, enterprise.variant());
    }

    @Test
    void openBreakerFallsBackInsteadOfFailing() {
        CircuitBreaker fp16 = breakers.get("fp16");
        for (int i = 0; i < 30; i++) {
            fp16.onFailure();
        }
        RoutingDecision.Routed r = routed(router.route(req(Segment.ENTERPRISE, 0.85, 1000)));
        assertEquals(INT8, r.variant());
        assertTrue(r.degraded());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("circuit open")));
    }

    @Test
    void neverServesBelowTheRequestsQualityFloor() {
        for (String v : List.of("fp16", "int8")) {
            for (int i = 0; i < 30; i++) {
                breakers.get(v).onFailure();
            }
        }
        assertInstanceOf(RoutingDecision.Rejected.class, router.route(req(Segment.ENTERPRISE, 0.85, 1000)));
    }

    @Test
    void contextWindowIsRespected() {
        latency.setPressure("fp16", 0.99);
        latency.setPressure("int8", 1.0);
        // 10k prompt does not fit the small model's 8k window, and both big pools are saturated.
        assertInstanceOf(RoutingDecision.Rejected.class, router.route(req(Segment.CONSUMER, 0, 10_000)));
    }

    @Test
    void shedRequestsAreRejectedBeforeRouting() {
        shedder.setLevel(1.0 / 3);
        RoutingDecision.Rejected r = assertInstanceOf(RoutingDecision.Rejected.class,
                router.route(req(Segment.CONSUMER, 0, 100)));
        assertTrue(r.reasons().get(0).startsWith("shed"));
    }

    @Test
    void requiresABreakerPerVariant() {
        assertThrows(IllegalArgumentException.class,
                () -> new LatencyAwareRouter(List.of(FP16), Map.of(), latency, shedder, 0.7));
    }
}
