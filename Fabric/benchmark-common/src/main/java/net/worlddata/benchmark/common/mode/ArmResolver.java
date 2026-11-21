package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.Arm;

import java.util.ArrayList;
import java.util.List;

public final class ArmResolver {

    public record Resolution(Arm arm, String error) {

        public boolean ok() {
            return error == null;
        }
    }

    private ArmResolver() {}

    public static Resolution resolve(ArmChoice choice, boolean coreInstalled) {
        ArmChoice picked = choice == null ? ArmChoice.AUTO : choice;
        Arm arm = switch (picked) {
            case CORE -> Arm.CORE;
            case VANILLA -> Arm.VANILLA;
            case AUTO -> coreInstalled ? Arm.CORE : Arm.VANILLA;
        };
        if (arm == Arm.CORE && !coreInstalled) {
            return new Resolution(null, "WorldData Core is not installed or not ready, so the Core source cannot run (choose vanilla for the Minecraft source)");
        }
        return new Resolution(arm, null);
    }

    public static String sourceText(Arm arm, ArmChoice requested, boolean coreInstalled) {
        String asked = (requested == null ? ArmChoice.AUTO : requested).text();
        if (arm == Arm.CORE) return "WorldData Core (requested: " + asked + ")";
        return coreInstalled
                ? "Minecraft (requested: " + asked + "; WorldData Core is installed but is not queried, its own tick work still runs)"
                : "Minecraft (requested: " + asked + "; WorldData Core is not installed)";
    }

    public static List<String> sourceLines(boolean coreInstalled, Arm auto) {
        List<String> lines = new ArrayList<>();
        lines.add("Sources: pick one for every run, no Minecraft restart needed: core, vanilla (also minecraft) or auto");
        lines.add("WorldData Core installed and ready: " + (coreInstalled ? "yes" : "no") + "; auto picks " + auto.displayName());
        lines.add("core: WorldData Core reads, Minecraft writes, and a paired Minecraft baseline runs beside every read family"
                + (coreInstalled ? "" : " (not available: WorldData Core is not installed)"));
        lines.add("vanilla: Minecraft reads and writes only"
                + (coreInstalled ? "; WorldData Core stays loaded and keeps doing its own tick work, remove its jar for a pure Minecraft baseline" : ""));
        lines.add("Examples: /worlddatatest run vanilla 200000 then /worlddatatest run core 200000 (same ops, seed and cap on both), ramp core 30, ramp vanilla 30, efficiency vanilla");
        lines.add("Times of both sources: /worlddatatest times");
        return lines;
    }
}
