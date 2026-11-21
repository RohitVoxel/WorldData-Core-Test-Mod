package net.worlddata.benchmark.common.check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class CheckFile {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private CheckFile() {}

    public static Path write(Path root, String name, List<String> lines) throws IOException {
        return write(root, name, lines, LocalDateTime.now());
    }

    static Path write(Path root, String name, List<String> lines, LocalDateTime when) throws IOException {
        if (name == null || !name.matches("[a-z0-9-]+")) throw new IllegalArgumentException("name must be lower case letters, digits and dashes");
        Path directory = root.resolve("checks");
        Files.createDirectories(directory);
        Path file = directory.resolve(name + "-" + STAMP.format(when) + ".txt");
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }
}
