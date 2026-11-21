package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.report.MiniJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ModePublisher {

    public record Result(ModeReport report, Path directory, boolean written, String summaryLine, String logLine) {
    }

    private static volatile Result last;

    private ModePublisher() {}

    public static Result publish(ModeRun run, Path reportsRoot) {
        ModeReport report = run.report();
        Path directory = reportsRoot.resolve(report.runId());
        boolean written = false;
        String line = report.summaryLine();
        String log = line;
        try {
            Files.createDirectories(directory);
            write(directory.resolve("report.json"), MiniJson.write(report.json()));
            write(directory.resolve("report.md"), report.markdown());
            for (Map.Entry<String, String> file : report.files().entrySet()) write(directory.resolve(file.getKey()), file.getValue());
            written = true;
            log = line + " - files in " + directory;
        } catch (IOException e) {
            line = line + " - the report files could not be written";
            log = line + ": " + e.getMessage();
        }
        Result result = new Result(report, directory, written, line, log);
        last = result;
        return result;
    }

    public static Result last() {
        return last;
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
