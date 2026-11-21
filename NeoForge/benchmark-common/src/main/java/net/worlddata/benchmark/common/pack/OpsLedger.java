package net.worlddata.benchmark.common.pack;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class OpsLedger {

    private final long total;
    private final LongAdder issued = new LongAdder();
    private final AtomicLong completed = new AtomicLong();
    private final LongAdder[] byOutcome = new LongAdder[Outcome.values().length];
    private final LongAdder[] byPhase = new LongAdder[PackPhase.values().length];
    private final LongAdder[][] byFamilyOutcome = new LongAdder[Family.values().length][];
    private final LongAdder reads = new LongAdder();
    private final LongAdder writes = new LongAdder();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger inFlightPeak = new AtomicInteger();
    private final LongAdder drainTimeouts = new LongAdder();
    private final LongAdder lightCreditsForced = new LongAdder();

    public OpsLedger(long total) {
        this.total = total;
        for (int i = 0; i < byOutcome.length; i++) byOutcome[i] = new LongAdder();
        for (int i = 0; i < byPhase.length; i++) byPhase[i] = new LongAdder();
        for (int i = 0; i < byFamilyOutcome.length; i++) {
            byFamilyOutcome[i] = new LongAdder[Outcome.values().length];
            for (int j = 0; j < byFamilyOutcome[i].length; j++) byFamilyOutcome[i][j] = new LongAdder();
        }
    }

    public long total() {
        return total;
    }

    public void issuedBulk(long count) {
        issued.add(count);
        reads.add(count);
    }

    public void completeBulk(Family family, Outcome outcome, PackPhase phase, long count) {
        if (!outcome.terminal()) throw new IllegalArgumentException("only a terminal outcome completes an op: " + outcome);
        completed.addAndGet(count);
        byOutcome[outcome.ordinal()].add(count);
        byPhase[phase.ordinal()].add(count);
        byFamilyOutcome[family.ordinal()][outcome.ordinal()].add(count);
    }

    public void issued(boolean write) {
        issued.increment();
        if (write) writes.increment();
        else reads.increment();
    }

    public void pending() {
        int now = inFlight.incrementAndGet();
        int peak = inFlightPeak.get();
        while (now > peak && !inFlightPeak.compareAndSet(peak, now)) peak = inFlightPeak.get();
    }

    public void settledPending(int waiters) {
        inFlight.addAndGet(-waiters);
    }

    public void complete(Family family, Outcome outcome, PackPhase phase) {
        if (!outcome.terminal()) throw new IllegalArgumentException("only a terminal outcome completes an op: " + outcome);
        completed.incrementAndGet();
        byOutcome[outcome.ordinal()].increment();
        byPhase[phase.ordinal()].increment();
        byFamilyOutcome[family.ordinal()][outcome.ordinal()].increment();
    }

    public void drainTimeout() {
        drainTimeouts.increment();
    }

    public void lightCreditForced() {
        lightCreditsForced.increment();
    }

    public long issued() {
        return issued.sum();
    }

    public long completed() {
        return completed.get();
    }

    public boolean reachedTotal() {
        return completed.get() >= total;
    }

    public long outcome(Outcome outcome) {
        return byOutcome[outcome.ordinal()].sum();
    }

    public long phaseCompleted(PackPhase phase) {
        return byPhase[phase.ordinal()].sum();
    }

    public long familyCompleted(Family family) {
        long sum = 0L;
        for (LongAdder adder : byFamilyOutcome[family.ordinal()]) sum += adder.sum();
        return sum;
    }

    public long familyOutcome(Family family, Outcome outcome) {
        return byFamilyOutcome[family.ordinal()][outcome.ordinal()].sum();
    }

    public long reads() {
        return reads.sum();
    }

    public long writes() {
        return writes.sum();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int inFlightPeak() {
        return inFlightPeak.get();
    }

    public long drainTimeouts() {
        return drainTimeouts.sum();
    }

    public long lightCreditsForced() {
        return lightCreditsForced.sum();
    }

    public boolean balanced() {
        return issued.sum() == completed.get() && inFlight.get() == 0;
    }
}
