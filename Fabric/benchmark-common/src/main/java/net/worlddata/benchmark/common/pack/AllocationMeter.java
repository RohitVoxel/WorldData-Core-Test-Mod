package net.worlddata.benchmark.common.pack;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

public final class AllocationMeter {

    private static final Method ALLOCATED;
    private static final Object BEAN;

    static {
        Method method = null;
        Object bean = null;
        try {
            bean = ManagementFactory.getThreadMXBean();
            Class<?> type = Class.forName("com.sun.management.ThreadMXBean");
            if (type.isInstance(bean)) {
                method = type.getMethod("getThreadAllocatedBytes", long.class);
                method.invoke(bean, Thread.currentThread().threadId());
            } else {
                bean = null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            method = null;
            bean = null;
        }
        ALLOCATED = method;
        BEAN = bean;
    }

    private AllocationMeter() {}

    public static boolean supported() {
        return ALLOCATED != null;
    }

    public static long threadBytes() {
        if (ALLOCATED == null) return -1L;
        try {
            return (Long) ALLOCATED.invoke(BEAN, Thread.currentThread().threadId());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1L;
        }
    }

    public static double bytesPerCall(int calls, Runnable loop) {
        if (!supported() || calls <= 0) return -1.0;
        long calibrationA = threadBytes();
        long calibrationB = threadBytes();
        long overhead = Math.max(0L, calibrationB - calibrationA);
        long before = threadBytes();
        loop.run();
        long after = threadBytes();
        if (before < 0L || after < 0L) return -1.0;
        return Math.max(0.0, (after - before - overhead) / (double) calls);
    }
}
