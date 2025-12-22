package io.autosreguard.inference;

import io.autosreguard.core.Preconditions;
import io.autosreguard.core.Segment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Priority-aware admission control that sheds the least critical traffic first.
 *
 * <h2>The shedding ladder</h2>
 * A single "shedding level" {@code L} in [0,1] is spread across segments in reverse priority
 * order. With three segments, each owns a third of the range:
 * <pre>
 *   L = 0.00 - 0.33   shed 0% -> 100% of CONSUMER
 *   L = 0.33 - 0.67   shed all CONSUMER, 0% -> 100% of API
 *   L = 0.67 - 1.00   shed all CONSUMER + API, then ENTERPRISE (last resort)
 * </pre>
 * This is how "protect core user journeys" becomes a single dial the remediation layer can
 * turn, and why enterprise tenants keep working while consumer traffic absorbs the incident.
 *
 * <h2>Deterministic decisions</h2>
 * Whether a request is shed is a pure function of its id, not a random coin flip, so retries
 * of a shed request are shed consistently rather than amplifying load as a retry storm.
 */
public final class LoadShedder {

    /** Segments ordered from first-to-shed to last-to-shed. */
    private static final List<Segment> SHED_ORDER = Arrays.stream(Segment.values())
            .sorted(Comparator.comparingInt(Segment::sheddingPriority).reversed())
            .toList();

    private volatile double level;

    /** Sets the shedding level in [0,1]. Called by the remediation layer's {@code ShedLoad}. */
    public void setLevel(double level) {
        this.level = Preconditions.checkUnitInterval(level, "level");
    }

    public double level() {
        return level;
    }

    /** Fraction of a given segment currently being shed. */
    public double sheddingFractionFor(Segment segment) {
        int n = SHED_ORDER.size();
        int rank = SHED_ORDER.indexOf(segment);
        double lo = (double) rank / n;
        double hi = (double) (rank + 1) / n;
        double l = level;
        if (l <= lo) {
            return 0.0;
        }
        if (l >= hi) {
            return 1.0;
        }
        return (l - lo) / (hi - lo);
    }

    /** Returns {@code true} if the request should be admitted. */
    public boolean admit(InferenceRequest req) {
        double f = sheddingFractionFor(req.segment());
        if (f <= 0) {
            return true;
        }
        if (f >= 1) {
            return false;
        }
        return bucket(req.requestId()) >= f;
    }

    /** Stable hash of the request id mapped to [0,1). */
    static double bucket(String id) {
        CRC32 crc = new CRC32();
        crc.update(id.getBytes(StandardCharsets.UTF_8));
        return (crc.getValue() & 0xFFFFFFFFL) / (double) (1L << 32);
    }
}
