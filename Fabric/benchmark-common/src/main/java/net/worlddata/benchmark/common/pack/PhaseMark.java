package net.worlddata.benchmark.common.pack;

public record PhaseMark(PackPhase phase, long atCompletedOps, long atWallMillis, long atTick) {
}
