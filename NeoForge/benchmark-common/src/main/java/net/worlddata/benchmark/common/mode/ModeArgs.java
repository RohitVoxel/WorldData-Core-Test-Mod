package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.PackConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ModeArgs {

    private final Map<String, String> values = new LinkedHashMap<>();

    private ModeArgs() {}

    public static ModeArgs parse(String text, List<String> positional) {
        ModeArgs args = new ModeArgs();
        if (text == null || text.isBlank()) return args;
        int next = 0;
        for (String token : text.trim().split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                String key = token.substring(0, eq).toLowerCase(Locale.ROOT);
                if (!positional.contains(key) && !key.equals("arm") && !key.equals("thrash") && !key.equals("note")) throw new IllegalArgumentException("unknown option '" + key + "', known: " + String.join(", ", positional) + ", arm, thrash, note");
                if (args.values.containsKey(key)) throw new IllegalArgumentException("option '" + key + "' is given twice");
                args.values.put(key, token.substring(eq + 1));
                continue;
            }
            while (next < positional.size() && args.values.containsKey(positional.get(next))) next++;
            if (next >= positional.size()) throw new IllegalArgumentException("too many values, expected at most: " + String.join(", ", positional));
            args.values.put(positional.get(next++), token);
        }
        return args;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public String text(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    public long longValue(String key, long fallback, long min, long max) {
        String raw = values.get(key);
        if (raw == null) return fallback;
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, got '" + raw + "'");
        }
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ", got " + value);
        return value;
    }

    public double doubleValue(String key, double fallback, double min, double max) {
        String raw = values.get(key);
        if (raw == null) return fallback;
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, got '" + raw + "'");
        }
        if (Double.isNaN(value) || value < min || value > max) throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ", got " + raw);
        return value;
    }

    public int thrashWorkers(String key) {
        String raw = values.get(key);
        if (raw == null) return 0;
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "off", "false", "no", "0":
                return 0;
            case "on", "true", "yes":
                return PackConfig.THRASH_WORKERS;
            default:
                break;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be off, on or a worker count from " + PackConfig.THRASH_WORKERS_MIN + " to " + PackConfig.THRASH_WORKERS_MAX + ", got '" + raw + "'");
        }
        if (value < PackConfig.THRASH_WORKERS_MIN || value > PackConfig.THRASH_WORKERS_MAX) {
            throw new IllegalArgumentException(key + " must be off, on or a worker count from " + PackConfig.THRASH_WORKERS_MIN + " to " + PackConfig.THRASH_WORKERS_MAX + ", got " + value);
        }
        return value;
    }

    public boolean flag(String key, boolean fallback) {
        String raw = values.get(key);
        if (raw == null) return fallback;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1" -> true;
            case "off", "false", "no", "0" -> false;
            default -> throw new IllegalArgumentException(key + " must be on or off, got '" + raw + "'");
        };
    }
}
