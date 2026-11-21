package net.worlddata.benchmark.common.pack;

public enum Outcome {
    PASS(true),
    PENDING(false),
    UNLOADED(true),
    UNAVAILABLE(true),
    TIMEOUT(true),
    FAIL(true);

    private final boolean terminal;

    Outcome(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }
}
