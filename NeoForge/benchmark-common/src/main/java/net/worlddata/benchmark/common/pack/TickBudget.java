package net.worlddata.benchmark.common.pack;

import java.util.function.LongSupplier;

public final class TickBudget {

    private final long limitNanos;
    private final LongSupplier clock;
    private long startNanos;
    private long deadlineNanos;

    public TickBudget(long limitNanos, LongSupplier clock) {
        this.limitNanos = limitNanos;
        this.clock = clock;
    }

    public void begin() {
        begin(clock.getAsLong());
    }

    public void begin(long nowNanos) {
        startNanos = nowNanos;
        deadlineNanos = nowNanos + limitNanos;
    }

    public boolean exhausted() {
        return clock.getAsLong() >= deadlineNanos;
    }

    public boolean usedAtLeast(int percent) {
        return clock.getAsLong() - startNanos >= limitNanos * percent / 100L;
    }

    public long remainingNanos() {
        return Math.max(0L, deadlineNanos - clock.getAsLong());
    }

    public long usedNanos() {
        return Math.max(0L, clock.getAsLong() - startNanos);
    }

    public long limitNanos() {
        return limitNanos;
    }
}
