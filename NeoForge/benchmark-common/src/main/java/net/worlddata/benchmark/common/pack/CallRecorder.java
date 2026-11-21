package net.worlddata.benchmark.common.pack;

import java.util.concurrent.atomic.LongAdder;

public final class CallRecorder {

    private static final CallKind[] KINDS = CallKind.values();
    private static final Outcome[] OUTCOMES = Outcome.values();

    private final LatencyHistogram[] histograms = new LatencyHistogram[KINDS.length];
    private final LongAdder[][] outcomes = new LongAdder[KINDS.length][];

    public CallRecorder() {
        for (int i = 0; i < KINDS.length; i++) {
            histograms[i] = new LatencyHistogram();
            outcomes[i] = new LongAdder[OUTCOMES.length];
            for (int j = 0; j < OUTCOMES.length; j++) outcomes[i][j] = new LongAdder();
        }
    }

    public void record(CallKind kind, long nanos, Outcome outcome) {
        int k = kind.ordinal();
        histograms[k].record(nanos);
        outcomes[k][outcome.ordinal()].increment();
    }

    public long count(CallKind kind) {
        return histograms[kind.ordinal()].count();
    }

    public long p50(CallKind kind) {
        return histograms[kind.ordinal()].percentile(0.50);
    }

    public CallSnapshot snapshot(CallKind kind) {
        int k = kind.ordinal();
        LatencyHistogram h = histograms[k];
        long n = h.count();
        if (n == 0L) return CallSnapshot.empty(kind);
        double fail = (outcomes[k][Outcome.FAIL.ordinal()].sum() + outcomes[k][Outcome.TIMEOUT.ordinal()].sum()) / (double) n;
        double unavailable = (outcomes[k][Outcome.UNAVAILABLE.ordinal()].sum() + outcomes[k][Outcome.UNLOADED.ordinal()].sum()) / (double) n;
        return new CallSnapshot(kind, n, outcomes[k][Outcome.PASS.ordinal()].sum(), outcomes[k][Outcome.PENDING.ordinal()].sum(), outcomes[k][Outcome.UNLOADED.ordinal()].sum(),
                outcomes[k][Outcome.UNAVAILABLE.ordinal()].sum(), outcomes[k][Outcome.TIMEOUT.ordinal()].sum(), outcomes[k][Outcome.FAIL.ordinal()].sum(), h.min(),
                h.percentile(0.50), h.percentile(0.95), h.percentile(0.99), h.mean(), h.max(), fail, unavailable);
    }

    public long familyCount(Arm arm, Family family) {
        long total = 0L;
        for (CallKind kind : KINDS) {
            if (kind.arm() == arm && kind.family() == family) total += count(kind);
        }
        return total;
    }

    public long armCount(Arm arm) {
        long total = 0L;
        for (CallKind kind : KINDS) {
            if (kind.arm() == arm) total += count(kind);
        }
        return total;
    }
}
