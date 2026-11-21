package net.worlddata.benchmark.common.pack;

public enum PackPhase {
    PRIME("Prime and populate", 0, 10),
    THRASH("Thrash", 10, 50),
    MUTATION("Mutation", 50, 85),
    SPILL("Spill and wind-down", 85, 100),
    DRAIN("Drain", 100, 100),
    FINISHED("Finished", 100, 100);

    private final String label;
    private final int fromPercent;
    private final int toPercent;

    PackPhase(String label, int fromPercent, int toPercent) {
        this.label = label;
        this.fromPercent = fromPercent;
        this.toPercent = toPercent;
    }

    public String label() {
        return label;
    }

    public int fromPercent() {
        return fromPercent;
    }

    public int toPercent() {
        return toPercent;
    }

    public static PackPhase of(long completed, long total) {
        if (total <= 0L) return SPILL;
        long percent = completed * 100L / total;
        if (percent < PRIME.toPercent) return PRIME;
        if (percent < THRASH.toPercent) return THRASH;
        if (percent < MUTATION.toPercent) return MUTATION;
        return SPILL;
    }
}
