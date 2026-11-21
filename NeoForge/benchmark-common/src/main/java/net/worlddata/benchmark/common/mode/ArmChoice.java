package net.worlddata.benchmark.common.mode;

import java.util.List;
import java.util.Locale;

public enum ArmChoice {
    AUTO,
    CORE,
    VANILLA;

    public static final List<String> LITERALS = List.of("auto", "core", "vanilla", "minecraft");

    public String text() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ArmChoice parse(String text) {
        if (text == null || text.isBlank()) return AUTO;
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "core", "worlddata", "worlddata-core" -> CORE;
            case "vanilla", "minecraft", "mc" -> VANILLA;
            default -> throw new IllegalArgumentException("arm must be auto, core or vanilla, got '" + text + "'");
        };
    }
}
