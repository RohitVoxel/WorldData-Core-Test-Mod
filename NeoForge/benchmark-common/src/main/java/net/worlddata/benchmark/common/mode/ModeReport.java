package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.Arm;

import java.util.Map;

public record ModeReport(
        String runId,
        String mode,
        Arm arm,
        Map<String, Object> json,
        String markdown,
        Map<String, String> files,
        String summaryLine
) {
}
