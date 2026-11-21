package net.worlddata.benchmark.common.bench;

import java.util.List;

public enum BenchMode {
    FIXED("fixed"),
    RANDOM("random"),
    ACQUIRE("acquire");

    private final String commandName;

    BenchMode(String commandName) {
        this.commandName = commandName;
    }

    public String commandName() {
        return commandName;
    }

    public boolean acquiresEveryCall() {
        return this == ACQUIRE;
    }

    public boolean spreadsPositions() {
        return this != FIXED;
    }

    public static List<String> names() {
        return List.of(FIXED.commandName, RANDOM.commandName, ACQUIRE.commandName);
    }

    public static BenchMode parse(String text) {
        if (text == null || text.isBlank()) return FIXED;
        String wanted = text.trim();
        for (BenchMode mode : values()) {
            if (mode.commandName.equalsIgnoreCase(wanted)) return mode;
        }
        throw new IllegalArgumentException("Unknown mode '" + wanted + "'. Choose one of: " + String.join(", ", names()));
    }
}
