package net.worlddata.benchmark.common.bench;

import java.util.ArrayList;
import java.util.List;

public final class BenchRun {

    public record Result(BenchConfig config, String dimension, BenchPositions positions, long timerFloorNanos,
                         List<BenchSideResult> sides) {
        public boolean compared() {
            return sides.size() > 1;
        }
    }

    private BenchRun() {}

    public static Result execute(BenchConfig config, String dimension, BenchPositions positions,
                                 List<BenchTarget> targets, BenchClock clock) {
        long floor = BenchLoop.timerFloor(clock);
        List<BenchSideResult> sides = new ArrayList<>();
        for (BenchTarget target : targets) {
            sides.add(BenchLoop.run(target, config, clock, BenchLoop.PASS_BUDGET_NANOS));
        }
        return new Result(config, dimension, positions, floor, List.copyOf(sides));
    }
}
