package net.worlddata.benchmark.common.pack;

import net.worlddata.benchmark.common.report.MiniJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PackReportPublisher {

    public record Result(PackReport report, Path directory, boolean written, String summaryLine, String logLine, java.util.List<String> latencyLines, java.util.List<String> coverageLines) {
    }

    private static volatile Result last;

    private PackReportPublisher() {}

    public static Result publish(PackSession session, Path reportsRoot) {
        Arm arm = session.setup().primary().arm();
        Arm otherArm = arm == Arm.CORE ? Arm.VANILLA : Arm.CORE;
        PackReport report = PackReportBuilder.build(session, ArmRunStore.load(reportsRoot, otherArm));
        Path directory = reportsRoot.resolve(report.runId());
        boolean written = false;
        String line = report.summaryLine();
        String log = line;
        try {
            Files.createDirectories(directory);
            write(directory.resolve("report.json"), MiniJson.write(report.json()));
            write(directory.resolve("report.md"), report.markdown());
            write(directory.resolve("report.csv"), report.wideCsv());
            write(directory.resolve("latency.csv"), report.latencyCsv());
            write(directory.resolve("curve.csv"), report.curveCsv());
            if (!session.stoppedEarly() && session.timeToFinishMillis() > 0L) {
                OpsLedger l = session.ledger();
                ArmRunStore.save(reportsRoot, new ArmRun(arm, report.runId(), l.total(), session.setup().seed(), l.completed(), session.timeToFinishMillis(), false, java.time.Instant.now().toString(),
                        session.restoreWait().percentile(0.5), session.restoreWait().count(), session.setup().opsPerSecond(), session.setup().coreInstalled()));
            }
            written = true;
            log = line + " - files in " + directory;
        } catch (IOException e) {
            line = line + " - the report files could not be written";
            log = line + ": " + e.getMessage();
        }
        Result result = new Result(report, directory, written, line, log, PackLiveStatus.latencyLines(session), PackLiveStatus.coverageLines(session));
        last = result;
        return result;
    }

    public static ArmRun saved(Path reportsRoot, Arm arm) {
        return ArmRunStore.load(reportsRoot, arm);
    }

    public static Result last() {
        return last;
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
