package net.worlddata.benchmark.common.mode;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

public final class GcMeter {

    private static final List<GarbageCollectorMXBean> BEANS = ManagementFactory.getGarbageCollectorMXBeans();

    private GcMeter() {}

    public static long totalMillis() {
        long sum = 0L;
        for (GarbageCollectorMXBean bean : BEANS) {
            long t = bean.getCollectionTime();
            if (t > 0L) sum += t;
        }
        return sum;
    }
}
