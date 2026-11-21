package net.worlddata.benchmark.common.pack;

public final class BatchProfile {

    private static final long MIX_SALT = 0x4D4958L;
    private static final int BASIS = 10_000;

    private final int batchNo;
    private final int[] cumulative = new int[OpKind.values().length];
    private final OpKind theme;

    private BatchProfile(int batchNo, long seed) {
        this.batchNo = batchNo;
        OpKind[] kinds = OpKind.values();
        long h = SeedMix.at(seed ^ MIX_SALT, batchNo);
        this.theme = kinds[SeedMix.bounded(h, 5, kinds.length)];
        double[] weight = new double[kinds.length];
        double sum = 0.0;
        for (int i = 0; i < kinds.length; i++) {
            long k = SeedMix.mix(h + (i + 1) * 0x9E3779B97F4A7C15L);
            double factor = 0.5 + SeedMix.bounded(k, 7, 1000) / 1000.0;
            if (kinds[i] == theme) factor *= 2.5;
            weight[i] = kinds[i].share() * factor;
            sum += weight[i];
        }
        int running = 0;
        for (int i = 0; i < kinds.length; i++) {
            int slice = i == kinds.length - 1 ? BASIS - running : Math.max(1, (int) Math.round(weight[i] / sum * BASIS));
            running += slice;
            cumulative[i] = Math.min(BASIS, running);
        }
        cumulative[kinds.length - 1] = BASIS;
    }

    public static BatchProfile of(long seed, int batchNo) {
        return new BatchProfile(batchNo, seed);
    }

    public int batchNo() {
        return batchNo;
    }

    public OpKind theme() {
        return theme;
    }

    public int shareBasisPoints(OpKind kind) {
        int i = kind.ordinal();
        return cumulative[i] - (i == 0 ? 0 : cumulative[i - 1]);
    }

    public OpKind kindOf(int basisPoint) {
        OpKind[] kinds = OpKind.values();
        for (int i = 0; i < kinds.length; i++) {
            if (basisPoint < cumulative[i]) return kinds[i];
        }
        return kinds[kinds.length - 1];
    }
}
