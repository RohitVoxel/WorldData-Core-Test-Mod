package net.worlddata.benchmark.common.pack;

public final class BaselineSampler {

    private static final int ATTEMPTS_PER_FLOOR_SAMPLE = 4;

    private final int[] passed = new int[ReadAction.values().length];
    private final long[] attempts = new long[ReadAction.values().length];

    public boolean due(ReadAction action, long index) {
        int slot = action.ordinal();
        boolean heavy = action.heavyBaseline();
        int floor = heavy ? PackConfig.HEAVY_BASELINE_FLOOR : PackConfig.BASELINE_FLOOR;
        if (passed[slot] < floor && attempts[slot] < (long) floor * ATTEMPTS_PER_FLOOR_SAMPLE) return true;
        return index % (heavy ? PackConfig.HEAVY_BASELINE_EVERY : PackConfig.BASELINE_EVERY) == 0L;
    }

    public void taken(ReadAction action, Outcome outcome) {
        int slot = action.ordinal();
        attempts[slot]++;
        if (outcome == Outcome.PASS) passed[slot]++;
    }

    public int passed(ReadAction action) {
        return passed[action.ordinal()];
    }

    public long attempts(ReadAction action) {
        return attempts[action.ordinal()];
    }

    public static String policyText() {
        return "every " + PackConfig.BASELINE_EVERY + "th op of a read action (every " + PackConfig.HEAVY_BASELINE_EVERY + "th for chunk, box and grid scans), plus the first "
                + PackConfig.BASELINE_FLOOR + " (" + PackConfig.HEAVY_BASELINE_FLOOR + " for scans) successful samples of every action";
    }
}
