package net.worlddata.benchmark.common.pack;

public final class OpsRate {

    private final long cap;
    private final long capacity;
    private long[] stamps = new long[128];
    private long[] counts = new long[128];
    private int head;
    private int size;
    private long windowSum;
    private long peak;
    private long total;
    private double tokens;
    private long lastNanos;
    private boolean started;

    public OpsRate(long capPerSecond) {
        this.cap = Math.max(0L, capPerSecond);
        this.capacity = PackConfig.rateCapacity(this.cap);
    }

    public long beginTick(long now) {
        expire(now);
        if (cap <= 0L) return Long.MAX_VALUE;
        long elapsed = started ? Math.max(0L, now - lastNanos) : PackConfig.RATE_NOMINAL_TICK_NANOS;
        started = true;
        lastNanos = now;
        tokens = Math.min(capacity, tokens + cap * (elapsed / 1_000_000_000.0));
        return Math.max(0L, Math.min((long) tokens, cap - windowSum));
    }

    public void endTick(long now, long issued) {
        if (issued > 0L) {
            tokens = Math.max(0.0, tokens - issued);
            push(now, issued);
            windowSum += issued;
            total += issued;
        }
        peak = Math.max(peak, windowSum);
    }

    public long cap() {
        return cap;
    }

    public long windowOps() {
        return windowSum;
    }

    public long peak() {
        return peak;
    }

    public long total() {
        return total;
    }

    private void push(long now, long count) {
        if (size == stamps.length) grow();
        int tail = (head + size) % stamps.length;
        stamps[tail] = now;
        counts[tail] = count;
        size++;
    }

    private void expire(long now) {
        long limit = now - PackConfig.RATE_WINDOW_NANOS;
        while (size > 0 && stamps[head] <= limit) {
            windowSum -= counts[head];
            head = (head + 1) % stamps.length;
            size--;
        }
    }

    private void grow() {
        long[] s = new long[stamps.length * 2];
        long[] c = new long[counts.length * 2];
        for (int i = 0; i < size; i++) {
            s[i] = stamps[(head + i) % stamps.length];
            c[i] = counts[(head + i) % counts.length];
        }
        stamps = s;
        counts = c;
        head = 0;
    }
}
