package net.worlddata.benchmark.common.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CheckReport {

    public record Entry(CheckStatus status, String text) {}

    private final String title;
    private final List<Entry> entries = new ArrayList<>();

    public CheckReport(String title) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be blank");
        this.title = title;
    }

    public String title() {
        return title;
    }

    public CheckReport add(CheckStatus status, String text) {
        entries.add(new Entry(status, text));
        return this;
    }

    public CheckReport pass(String text) {
        return add(CheckStatus.PASS, text);
    }

    public CheckReport warn(String text) {
        return add(CheckStatus.WARN, text);
    }

    public CheckReport fail(String text) {
        return add(CheckStatus.FAIL, text);
    }

    public CheckReport info(String text) {
        return add(CheckStatus.INFO, text);
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public int count(CheckStatus status) {
        int n = 0;
        for (Entry entry : entries) if (entry.status() == status) n++;
        return n;
    }

    public CheckStatus verdict() {
        if (count(CheckStatus.FAIL) > 0) return CheckStatus.FAIL;
        if (count(CheckStatus.WARN) > 0) return CheckStatus.WARN;
        return CheckStatus.PASS;
    }

    public String summary() {
        return String.format(Locale.ROOT, "%s: %s (%d pass, %d warn, %d fail)", title, verdict(), count(CheckStatus.PASS),
                count(CheckStatus.WARN), count(CheckStatus.FAIL));
    }

    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add(title);
        lines.add("Verdict: " + verdict() + " (" + count(CheckStatus.PASS) + " pass, " + count(CheckStatus.WARN) + " warn, "
                + count(CheckStatus.FAIL) + " fail)");
        for (Entry entry : entries) lines.add("[" + entry.status() + "] " + entry.text());
        return lines;
    }
}
