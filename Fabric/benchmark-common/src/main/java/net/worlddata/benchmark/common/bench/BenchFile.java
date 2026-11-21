package net.worlddata.benchmark.common.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class BenchFile {

    public static final String FOLDER = "bench";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private BenchFile() {}

    public static String stamp(LocalDateTime when) {
        return STAMP.format(when);
    }

    public static String fileName(BenchApi api, boolean compare, LocalDateTime when, int attempt) {
        return api.commandName() + (compare ? "-compare-" : "-") + STAMP.format(when) + (attempt > 0 ? "-" + (attempt + 1) : "") + ".txt";
    }

    public static Path write(Path root, BenchApi api, boolean compare, LocalDateTime when, List<String> lines) throws IOException {
        Path folder = root.resolve(FOLDER);
        Files.createDirectories(folder);
        Path file = folder.resolve(fileName(api, compare, when, 0));
        for (int attempt = 1; Files.exists(file); attempt++) {
            file = folder.resolve(fileName(api, compare, when, attempt));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }
}
