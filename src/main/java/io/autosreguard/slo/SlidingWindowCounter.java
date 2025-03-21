package io.autosreguard.slo;

import io.autosreguard.core.Preconditions;

import java.time.Duration;
import java.time.Instant;

/**
 * A fixed-memory, time-bucketed counter of good/total events.
 *
 * <h2>Why buckets instead of a list of events?</h2>
 * At a billion requests a day you cannot keep individual events in memory. A ring buffer
 * of time buckets gives O(1) writes and O(buckets) reads with bounded memory, at the cost of
 * bucket-width granularity, which is fine for burn-rate windows measured in minutes and hours.
 *
 * <h2>Memory</h2>
 * {@code horizon / bucketWidth} buckets x 3 longs. With the default 1-minute buckets and a
 * 24h horizon that is 1,440 buckets, roughly 35 KiB per series. For fleets with very high
 * tenant cardinality, run tenant tracking with coarser buckets or shard trackers by tenant hash.
 *
 * <h2>Thread safety</h2>
 * All methods are {@code synchronized}. Contention is low in practice because the tracker
 * holds one counter per (SLO, scope) and writes are tiny. If profiling ever says otherwise,
 * stripe by bucket index; do not reach for lock-free cleverness first.
 */
public final class SlidingWindowCounter {

    private final long bucketMillis;
    private final int bucketCount;

    /** The absolute bucket index (epochMillis / bucketMillis) each slot currently holds. */
    private final long[] bucketEpoch;
    private final long[] good;
    private final long[] total;

    public SlidingWindowCounter(Duration horizon, Duration bucketWidth) {
        this.bucketMillis = Preconditions.checkPositive(bucketWidth.toMillis(), "bucketWidth");
        long buckets = Math.ceilDiv(horizon.toMillis(), bucketMillis);
        if (buckets <= 0 || buckets > 1_000_000) {
            throw new IllegalArgumentException("unreasonable bucket count " + buckets
                    + " for horizon " + horizon + " / width " + bucketWidth);
        }
        this.bucketCount = (int) buckets;
        this.bucketEpoch = new long[bucketCount];
        this.good = new long[bucketCount];
        this.total = new long[bucketCount];
        java.util.Arrays.fill(bucketEpoch, Long.MIN_VALUE);
    }

    /** Records {@code n} events of which {@code goodCount} were good, at time {@code at}. */
    public synchronized void record(Instant at, long goodCount, long n) {
        if (goodCount < 0 || n < 0 || goodCount > n) {
            throw new IllegalArgumentException("invalid counts good=" + goodCount + " total=" + n);
        }
        long epoch = Math.floorDiv(at.toEpochMilli(), bucketMillis);
        int slot = Math.floorMod(epoch, bucketCount);
        if (bucketEpoch[slot] != epoch) {
            // The slot holds data from a previous lap around the ring (or nothing). Reset it.
            // Note: we accept late events only if their bucket is still resident; very late
            // events for an overwritten slot would corrupt newer data, so they are dropped below.
            if (bucketEpoch[slot] > epoch) {
                return; // stale event, older than the ring's horizon
            }
            bucketEpoch[slot] = epoch;
            good[slot] = 0;
            total[slot] = 0;
        }
        good[slot] += goodCount;
        total[slot] += n;
    }

    public void record(Instant at, boolean isGood) {
        record(at, isGood ? 1 : 0, 1);
    }

    /** Snapshot of counts over the trailing {@code window} ending at {@code now}. */
    public synchronized Counts sum(Duration window, Instant now) {
        long nowEpoch = Math.floorDiv(now.toEpochMilli(), bucketMillis);
        long windowBuckets = Math.min(bucketCount, Math.max(1, Math.ceilDiv(window.toMillis(), bucketMillis)));
        long oldest = nowEpoch - windowBuckets + 1;
        long g = 0;
        long t = 0;
        for (int i = 0; i < bucketCount; i++) {
            long e = bucketEpoch[i];
            if (e >= oldest && e <= nowEpoch) {
                g += good[i];
                t += total[i];
            }
        }
        return new Counts(g, t);
    }

    /** Longest window this counter can answer questions about. */
    public Duration horizon() {
        return Duration.ofMillis(bucketMillis * bucketCount);
    }

    /**
     * Immutable good/total pair.
     *
     * @param good  good events
     * @param total valid events
     */
    public record Counts(long good, long total) {

        /** Fraction of bad events, or 0 when there is no traffic (no traffic is not an outage). */
        public double errorRate() {
            return total == 0 ? 0.0 : (double) (total - good) / total;
        }

        public long bad() {
            return total - good;
        }
    }
}
