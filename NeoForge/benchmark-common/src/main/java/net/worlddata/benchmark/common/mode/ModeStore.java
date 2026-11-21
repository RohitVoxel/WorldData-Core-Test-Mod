package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.Arm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModeStore {

    private ModeStore() {}

    private static Path file(Path root, String mode, Arm arm) {
        return root.resolve("last-" + mode + "-" + arm.fileId() + ".txt");
    }

    public static void save(Path root, String mode, Arm arm, Map<String, String> values) {
        if (values.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue().replace('\n', ' ')).append('\n');
        try {
            Files.createDirectories(root);
            Files.writeString(file(root, mode, arm), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return;
        }
    }

    public static Map<String, String> load(Path root, String mode, Arm arm) {
        Map<String, String> values = new LinkedHashMap<>();
        Path file = file(root, mode, arm);
        if (!Files.isRegularFile(file)) return values;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                int eq = line.indexOf('=');
                if (eq > 0) values.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (IOException e) {
            values.clear();
        }
        return values;
    }
}
