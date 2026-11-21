package net.worlddata.benchmark.common.pack;

import java.util.Map;

public record PackReport(
        String runId,
        Arm arm,
        Map<String, Object> json,
        String markdown,
        String wideCsv,
        String latencyCsv,
        String curveCsv,
        String summaryLine
) {
}
