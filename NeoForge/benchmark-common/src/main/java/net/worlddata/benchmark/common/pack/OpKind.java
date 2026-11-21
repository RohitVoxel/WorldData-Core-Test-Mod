package net.worlddata.benchmark.common.pack;

public enum OpKind {
    POINT(24, true),
    LAYER(18, true),
    FILTER(14, true),
    SPATIAL(14, false),
    LIGHT(10, false),
    META(10, false),
    LOOKUP(10, false);

    private final int share;
    private final boolean stable;

    OpKind(int share, boolean stable) {
        this.share = share;
        this.stable = stable;
    }

    public int share() {
        return share;
    }

    public boolean stable() {
        return stable;
    }
}
