package net.worlddata.benchmark.corecheck;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.worlddata.benchmark.common.check.CheckFile;
import net.worlddata.benchmark.common.check.CheckReport;
import net.worlddata.benchmark.common.check.CheckStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class CheckOutput {

    private CheckOutput() {}

    static void fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text));
    }

    static void send(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    static void publish(CommandSourceStack source, CheckReport report, String fileName, Path root) {
        String color = switch (report.verdict()) {
            case PASS -> "§a";
            case WARN -> "§e";
            default -> "§c";
        };
        send(source, color + report.summary());
        for (CheckReport.Entry entry : report.entries()) {
            if (entry.status() == CheckStatus.FAIL || entry.status() == CheckStatus.WARN) {
                send(source, (entry.status() == CheckStatus.FAIL ? "§c" : "§e") + "[" + entry.status() + "] " + shorten(entry.text(), 300));
            }
        }
        List<String> lines = report.lines();
        try {
            Path file = CheckFile.write(root, fileName, lines);
            send(source, "§7Full report: " + file);
        } catch (IOException | RuntimeException failure) {
            send(source, "§eCould not write the report file: " + failure.getMessage());
        }
    }

    static String shorten(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 3) + "...";
    }
}
