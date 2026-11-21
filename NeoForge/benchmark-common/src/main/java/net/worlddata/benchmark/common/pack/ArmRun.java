package net.worlddata.benchmark.common.pack;

public record ArmRun(Arm arm, String runId, long totalOps, long seed, long completedOps, long finishMillis, boolean stoppedEarly, String finishedAt, long restoreP50Nanos, long restoreCount, long opsPerSecond, boolean coreInstalled) {

    public ArmRun(Arm arm, String runId, long totalOps, long seed, long completedOps, long finishMillis, boolean stoppedEarly, String finishedAt, long restoreP50Nanos, long restoreCount, long opsPerSecond) {
        this(arm, runId, totalOps, seed, completedOps, finishMillis, stoppedEarly, finishedAt, restoreP50Nanos, restoreCount, opsPerSecond, arm == Arm.CORE);
    }

    public ArmRun(Arm arm, String runId, long totalOps, long seed, long completedOps, long finishMillis, boolean stoppedEarly, String finishedAt, long restoreP50Nanos, long restoreCount) {
        this(arm, runId, totalOps, seed, completedOps, finishMillis, stoppedEarly, finishedAt, restoreP50Nanos, restoreCount, 0L);
    }

    public boolean usable() {
        return !stoppedEarly && finishMillis > 0L && completedOps >= totalOps;
    }

    public boolean minecraftWithCoreInstalled() {
        return arm == Arm.VANILLA && coreInstalled;
    }
}
