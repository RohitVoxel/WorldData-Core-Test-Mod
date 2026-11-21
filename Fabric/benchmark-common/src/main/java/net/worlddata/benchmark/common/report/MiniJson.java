package net.worlddata.benchmark.common.report;

import java.util.List;
import java.util.Map;

public final class MiniJson {

    private MiniJson() {}

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, (Map<String, Object>) map, indent);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, indent);
        } else if (value instanceof Enum<?> e) {
            writeString(sb, e.name());
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            indent(sb, indent + 1);
            writeString(sb, entry.getKey());
            sb.append(": ");
            writeValue(sb, entry.getValue(), indent + 1);
            if (++i < map.size()) sb.append(',');
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(sb, indent + 1);
            writeValue(sb, list.get(i), indent + 1);
            if (i < list.size() - 1) sb.append(',');
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int level) {
        sb.append("  ".repeat(level));
    }
}
