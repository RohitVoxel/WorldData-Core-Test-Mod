package net.worlddata.benchmark.common.pack;

public final class ReportNotes {

    public static final String HARNESS = "Results from WorldData Core Test, a dedicated benchmark mod running a synthetic workload. They are not a guarantee of TPS or MSPT in any modpack.";

    public static final String PACK_DETAIL = "Everything else is in the files next to this report: report.json (batches, force-load waves, thrash workers, block-write check, lag probes, allocation), latency.csv (min / p95 / mean / max per call), report.csv and curve.csv.";

    private ReportNotes() {}
}
