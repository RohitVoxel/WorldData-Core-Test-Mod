package net.worlddata.benchmark.common.pack;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public final class LatencyHistogram {

    private static final int EXACT = 16;
    private static final int SUB_BITS = 3;
    private static final int SUBS = 1 << SUB_BITS;
    private static final int BUCKETS = EXACT + (63 - 4 + 1) * SUBS;

    private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong sum = new AtomicLong();
    private final AtomicLong max = new AtomicLong();
    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);

    public void record(long nanos) {
        long value = Math.max(0L, nanos);
        counts.incrementAndGet(indexOf(value));
        total.incrementAndGet();
        sum.addAndGet(value);
        long seen = max.get();
        while (value > seen && !max.compareAndSet(seen, value)) seen = max.get();
        long low = min.get();
        while (value < low && !min.compareAndSet(low, value)) low = min.get();
    }

    public long count() {
        return total.get();
    }

    public long max() {
        return max.get();
    }

    public long min() {
        long v = min.get();
        return v == Long.MAX_VALUE ? 0L : v;
    }

    public double mean() {
        long n = total.get();
        return n == 0L ? 0.0 : sum.get() / (double) n;
    }

    public long percentile(double fraction) {
        long n = total.get();
        if (n == 0L) return 0L;
        long rank = Math.max(1L, (long) Math.ceil(fraction * n));
        long running = 0L;
        for (int i = 0; i < BUCKETS; i++) {
            running += counts.get(i);
            if (running >= rank) return Math.min(representative(i), max.get());
        }
        return max.get();
    }

    static int indexOf(long value) {
        if (value < EXACT) return (int) value;
        int msb = 63 - Long.numberOfLeadingZeros(value);
        int sub = (int) ((value >>> (msb - SUB_BITS)) & (SUBS - 1));
        return EXACT + (msb - 4) * SUBS + sub;
    }

    static long representative(int index) {
        if (index < EXACT) return index;
        int group = (index - EXACT) / SUBS;
        int sub = (index - EXACT) % SUBS;
        int msb = group + 4;
        long lower = (1L << msb) | ((long) sub << (msb - SUB_BITS));
        long width = 1L << (msb - SUB_BITS);
        return lower + width / 2;
    }
}
