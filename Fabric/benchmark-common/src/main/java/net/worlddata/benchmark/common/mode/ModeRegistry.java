package net.worlddata.benchmark.common.mode;

import java.util.concurrent.atomic.AtomicReference;

public final class ModeRegistry {

    private static final AtomicReference<ModeRun> ACTIVE = new AtomicReference<>();

    private ModeRegistry() {}

    public static ModeRun active() {
        return ACTIVE.get();
    }

    public static boolean trySet(ModeRun run) {
        return ACTIVE.compareAndSet(null, run);
    }

    public static boolean clear(ModeRun run) {
        return ACTIVE.compareAndSet(run, null);
    }
}
