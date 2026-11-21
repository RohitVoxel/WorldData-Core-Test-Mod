package net.worlddata.benchmark.common.pack;

public enum Arm {
    VANILLA("Minecraft"),
    CORE("WorldData Core");

    private final String displayName;

    Arm(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public String fileId() {
        return this == VANILLA ? "minecraft" : "worlddata-core";
    }
}
