package net.worlddata.benchmark.corecheck;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

public final class CoreCheckScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger("worlddata_core_test");

    private record Task(long dueTick, Consumer<MinecraftServer> work) {}

    private static final List<Task> TASKS = new ArrayList<>();

    private CoreCheckScheduler() {}

    public static void schedule(MinecraftServer server, int delayTicks, Consumer<MinecraftServer> work) {
        TASKS.add(new Task(server.getTickCount() + Math.max(1, delayTicks), work));
    }

    public static void tick(MinecraftServer server) {
        if (TASKS.isEmpty()) return;
        long now = server.getTickCount();
        List<Task> due = new ArrayList<>();
        Iterator<Task> iterator = TASKS.iterator();
        while (iterator.hasNext()) {
            Task task = iterator.next();
            if (task.dueTick() <= now) {
                due.add(task);
                iterator.remove();
            }
        }
        for (Task task : due) {
            try {
                task.work().accept(server);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("Core check task failed", failure);
            }
        }
    }

    public static void clear() {
        TASKS.clear();
    }

    public static int pending() {
        return TASKS.size();
    }
}
