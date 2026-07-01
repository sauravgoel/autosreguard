package io.autosreguard.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsRegistryTest {

    @Test
    void countersAreSharedPerNameAndLabelSet() {
        MetricsRegistry m = new MetricsRegistry();
        MetricsRegistry.Counter a = m.counter("x_total", "help", Map.of("tier", "AUTO"));
        MetricsRegistry.Counter b = m.counter("x_total", "help", Map.of("tier", "AUTO"));
        assertSame(a, b);
        a.inc();
        b.add(2);
        assertEquals(3, a.get());
    }

    @Test
    void scrapeIsPrometheusTextFormatWithSortedEscapedLabels() {
        MetricsRegistry m = new MetricsRegistry();
        m.counter("req_total", "Requests", Map.of("z", "1", "a", "quote\"d")).inc();
        String out = m.scrape();
        assertTrue(out.contains("# HELP req_total Requests"));
        assertTrue(out.contains("# TYPE req_total counter"));
        assertTrue(out.contains("req_total{a=\"quote\\\"d\",z=\"1\"} 1"), out);
    }

    @Test
    void gaugesAreReadAtScrapeTimeAndCanBeRemoved() {
        MetricsRegistry m = new MetricsRegistry();
        double[] v = {1.5};
        m.gauge("budget", "Budget", Map.of("scope", "tenant=acme"), () -> v[0]);
        v[0] = 0.25;
        assertTrue(m.scrape().contains("budget{scope=\"tenant=acme\"} 0.25"));
        m.removeGauge("budget", Map.of("scope", "tenant=acme"));
        assertFalse(m.scrape().contains("tenant=acme"), "tenant series must not leak forever");
    }

    @Test
    void histogramsEmitCumulativeBucketsSumAndCount() {
        MetricsRegistry m = new MetricsRegistry();
        MetricsRegistry.Histogram h = m.histogram("ttft_ms", "TTFT", Map.of(), 100, 500);
        h.observe(50);
        h.observe(200);
        h.observe(900);
        String out = m.scrape();
        assertTrue(out.contains("ttft_ms_bucket{le=\"100\"} 1"), out);
        assertTrue(out.contains("ttft_ms_bucket{le=\"500\"} 2"), out);
        assertTrue(out.contains("ttft_ms_bucket{le=\"+Inf\"} 3"), out);
        assertTrue(out.contains("ttft_ms_count 3"), out);
        assertTrue(out.contains("ttft_ms_sum 1150"), out);
    }
}
