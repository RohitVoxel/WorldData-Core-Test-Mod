package net.worlddata.benchmark.common.pack;

import java.util.concurrent.atomic.AtomicReference;

public final class PackSessionRegistry {

    private static final AtomicReference<PackSession> ACTIVE = new AtomicReference<>();

    private PackSessionRegistry() {}

    public static PackSession active() {
        return ACTIVE.get();
    }

    public static boolean trySet(PackSession session) {
        return ACTIVE.compareAndSet(null, session);
    }

    public static boolean clear(PackSession session) {
        return ACTIVE.compareAndSet(session, null);
    }
}
