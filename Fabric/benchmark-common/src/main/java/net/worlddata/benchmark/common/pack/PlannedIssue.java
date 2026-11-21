package net.worlddata.benchmark.common.pack;

public final class PlannedIssue {

    public OpKind kind;
    public ReadAction action;
    public boolean write;
    public boolean hotspot;
    public boolean coldProbe;
    public boolean shared;
    public int cell;
    public int worker;
    public int slot;
    public int localX;
    public int localZ;
    public int y;
    public int extra;
    public long hash;
}
