package net.worlddata.benchmark.common.pack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ArmRunStore {

    private ArmRunStore() {}

    public static void save(Path reportsRoot, ArmRun run) throws IOException {
        Files.createDirectories(reportsRoot);
        Properties p = new Properties();
        p.setProperty("arm", run.arm().name());
        p.setProperty("runId", run.runId());
        p.setProperty("totalOps", Long.toString(run.totalOps()));
        p.setProperty("seed", Long.toString(run.seed()));
        p.setProperty("completedOps", Long.toString(run.completedOps()));
        p.setProperty("finishMillis", Long.toString(run.finishMillis()));
        p.setProperty("stoppedEarly", Boolean.toString(run.stoppedEarly()));
        p.setProperty("finishedAt", run.finishedAt());
        p.setProperty("restoreP50Nanos", Long.toString(run.restoreP50Nanos()));
        p.setProperty("restoreCount", Long.toString(run.restoreCount()));
        p.setProperty("opsPerSecond", Long.toString(run.opsPerSecond()));
        p.setProperty("coreInstalled", Boolean.toString(run.coreInstalled()));
        try (Writer out = Files.newBufferedWriter(file(reportsRoot, run.arm()), StandardCharsets.UTF_8)) {
            p.store(out, "latest finished run of this arm");
        }
    }

    public static ArmRun load(Path reportsRoot, Arm arm) {
        Path file = file(reportsRoot, arm);
        if (!Files.isRegularFile(file)) return null;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
            return new ArmRun(arm, p.getProperty("runId", "?"), Long.parseLong(p.getProperty("totalOps", "0")), Long.parseLong(p.getProperty("seed", "0")),
                    Long.parseLong(p.getProperty("completedOps", "0")), Long.parseLong(p.getProperty("finishMillis", "0")),
                    Boolean.parseBoolean(p.getProperty("stoppedEarly", "false")), p.getProperty("finishedAt", ""),
                    Long.parseLong(p.getProperty("restoreP50Nanos", "0")), Long.parseLong(p.getProperty("restoreCount", "0")), Long.parseLong(p.getProperty("opsPerSecond", "0")),
                    Boolean.parseBoolean(p.getProperty("coreInstalled", arm == Arm.CORE ? "true" : "false")));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static Path file(Path reportsRoot, Arm arm) {
        return reportsRoot.resolve("latest-" + arm.fileId() + ".properties");
    }

    public static java.util.List<String> describe(Path reportsRoot) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        ArmRun minecraft = load(reportsRoot, Arm.VANILLA);
        ArmRun core = load(reportsRoot, Arm.CORE);
        lines.add("Time to finish all ops (latest finished run of each side)");
        lines.add(line(Arm.VANILLA, minecraft));
        lines.add(line(Arm.CORE, core));
        if (minecraft != null && core != null && minecraft.usable() && core.usable()) {
            if (minecraft.totalOps() != core.totalOps() || minecraft.seed() != core.seed() || minecraft.opsPerSecond() != core.opsPerSecond()) {
                lines.add("Not comparable: the two runs used a different op count, seed or ops per second cap.");
            } else {
                long diff = Math.abs(minecraft.finishMillis() - core.finishMillis());
                String faster = core.finishMillis() < minecraft.finishMillis() ? Arm.CORE.displayName() : Arm.VANILLA.displayName();
                lines.add(diff == 0L ? "Both finished in the same time." : faster + " was faster by " + PackReportBuilder.minutes(diff) + ".");
            }
        } else {
            lines.add("Run the same op count with and without WorldData Core to compare.");
        }
        return lines;
    }

    private static String line(Arm arm, ArmRun run) {
        if (run == null || !run.usable()) return arm.displayName() + ": not run yet";
        return arm.displayName() + ": " + PackReportBuilder.minutes(run.finishMillis()) + " for " + run.totalOps() + " ops (" + run.runId() + ")"
                + (run.minecraftWithCoreInstalled() ? " [WorldData Core was installed but not queried]" : "");
    }
}
