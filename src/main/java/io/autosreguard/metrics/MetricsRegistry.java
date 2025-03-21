package io.autosreguard.metrics;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * Dependency-free metrics registry with Prometheus text exposition.
 *
 * <p>SRE tooling must be observable too: when the autonomous layer acts, you need to see
 * what it did and how often, from the same dashboards as everything else. Metric families are
 * {@code name{labels}} with stable, low-cardinality labels. Per-tenant series are exposed only
 * for tenants currently firing to avoid a cardinality explosion.
 */
public final class MetricsRegistry {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");

    private final Map<String, Family> families = new ConcurrentHashMap<>();

    private enum Type { COUNTER, GAUGE, HISTOGRAM }

    private record Family(String name, String help, Type type, double[] buckets,
                          Map<SortedMap<String, String>, Object> series) {
    }

    /** Monotonic counter. */
    public Counter counter(String name, String help, Map<String, String> labels) {
        Family f = family(name, help, Type.COUNTER, null);
        return (Counter) f.series().computeIfAbsent(sorted(labels), k -> new Counter());
    }

    /** Gauge backed by a supplier evaluated at scrape time. Re-registering replaces the supplier. */
    public void gauge(String name, String help, Map<String, String> labels, DoubleSupplier supplier) {
        Family f = family(name, help, Type.GAUGE, null);
        f.series().put(sorted(labels), supplier);
    }

    /** Removes a gauge series (e.g. when a tenant stops firing). */
    public void removeGauge(String name, Map<String, String> labels) {
        Family f = families.get(name);
        if (f != null) {
            f.series().remove(sorted(labels));
        }
    }

    /** Fixed-bucket histogram; bucket upper bounds must be strictly increasing. */
    public Histogram histogram(String name, String help, Map<String, String> labels, double... buckets) {
        Family f = family(name, help, Type.HISTOGRAM, buckets);
        return (Histogram) f.series().computeIfAbsent(sorted(labels), k -> new Histogram(f.buckets()));
    }

    private Family family(String name, String help, Type type, double[] buckets) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid metric name " + name);
        }
        Family f = families.computeIfAbsent(name, n -> new Family(n, help, type,
                buckets == null ? null : buckets.clone(), new ConcurrentHashMap<>()));
        if (f.type() != type) {
            throw new IllegalArgumentException("metric " + name + " already registered as " + f.type());
        }
        return f;
    }

    private static SortedMap<String, String> sorted(Map<String, String> labels) {
        return new TreeMap<>(Objects.requireNonNull(labels, "labels"));
    }

    /** Renders all metrics in Prometheus text exposition format 0.0.4. */
    public String scrape() {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(families).forEach((name, f) -> {
            sb.append("# HELP ").append(name).append(' ').append(f.help()).append('\n');
            sb.append("# TYPE ").append(name).append(' ').append(f.type().name().toLowerCase(Locale.ROOT)).append('\n');
            f.series().forEach((labels, s) -> {
                switch (f.type()) {
                    case COUNTER -> line(sb, name, labels, ((Counter) s).get());
                    case GAUGE -> line(sb, name, labels, ((DoubleSupplier) s).getAsDouble());
                    case HISTOGRAM -> ((Histogram) s).render(sb, name, labels);
                }
            });
        });
        return sb.toString();
    }

    static void line(StringBuilder sb, String name, Map<String, String> labels, double v) {
        sb.append(name);
        if (!labels.isEmpty()) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, String> e : labels.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append("=\"")
                        .append(e.getValue().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
                        .append('"');
            }
            sb.append('}');
        }
        sb.append(' ').append(Double.isInfinite(v) ? (v > 0 ? "+Inf" : "-Inf") : formatValue(v)).append('\n');
    }

    private static String formatValue(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }

    /** Monotonic counter backed by {@link LongAdder} for low-contention increments. */
    public static final class Counter {
        private final LongAdder adder = new LongAdder();

        public void inc() {
            adder.increment();
        }

        public void add(long n) {
            if (n < 0) {
                throw new IllegalArgumentException("counters only go up");
            }
            adder.add(n);
        }

        public long get() {
            return adder.sum();
        }
    }

    /** Cumulative-bucket histogram in Prometheus style. */
    public static final class Histogram {
        private final double[] bounds;
        private final LongAdder[] counts;
        private final DoubleAdder sum = new DoubleAdder();
        private final LongAdder count = new LongAdder();

        Histogram(double[] bounds) {
            for (int i = 1; i < bounds.length; i++) {
                if (bounds[i] <= bounds[i - 1]) {
                    throw new IllegalArgumentException("histogram buckets must be increasing");
                }
            }
            this.bounds = bounds.clone();
            this.counts = new LongAdder[bounds.length];
            Arrays.setAll(counts, i -> new LongAdder());
        }

        public void observe(double v) {
            for (int i = 0; i < bounds.length; i++) {
                if (v <= bounds[i]) {
                    counts[i].increment();
                    break;
                }
            }
            sum.add(v);
            count.increment();
        }

        void render(StringBuilder sb, String name, SortedMap<String, String> labels) {
            long cumulative = 0;
            for (int i = 0; i < bounds.length; i++) {
                cumulative += counts[i].sum();
                SortedMap<String, String> l = new TreeMap<>(labels);
                l.put("le", formatValue(bounds[i]));
                line(sb, name + "_bucket", l, cumulative);
            }
            SortedMap<String, String> inf = new TreeMap<>(labels);
            inf.put("le", "+Inf");
            line(sb, name + "_bucket", inf, count.sum());
            line(sb, name + "_sum", labels, sum.sum());
            line(sb, name + "_count", labels, count.sum());
        }
    }
}
