package net.worlddata.benchmark.common.pack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public final class InFlightTable {

    public interface Settler {
        void settle(long token, Family family, Outcome outcome, long issueNanos, long nowNanos);
    }

    private static final class Entry {
        final long token;
        long[] issueNanos = new long[4];
        Family[] families = new Family[4];
        int count;
        int slot;

        Entry(long token) {
            this.token = token;
        }

        void add(long nanos, Family family) {
            if (count == issueNanos.length) {
                issueNanos = Arrays.copyOf(issueNanos, count * 2);
                families = Arrays.copyOf(families, count * 2);
            }
            issueNanos[count] = nanos;
            families[count] = family;
            count++;
        }
    }

    private final HashMap<Long, Entry> byToken = new HashMap<>();
    private final List<Entry> order = new ArrayList<>();
    private int cursor;
    private int waiters;
    private long watchdogTimeouts;

    public int waiters() {
        return waiters;
    }

    public int tokens() {
        return order.size();
    }

    public void add(long token, long issueNanos, Family family) {
        Entry entry = byToken.get(token);
        if (entry == null) {
            entry = new Entry(token);
            entry.slot = order.size();
            byToken.put(token, entry);
            order.add(entry);
        }
        entry.add(issueNanos, family);
        waiters++;
    }

    public long watchdogTimeouts() {
        return watchdogTimeouts;
    }

    public int poll(int maxEntries, WorldReader reader, long nowNanos, Settler settler) {
        return poll(maxEntries, reader, nowNanos, settler, null, 100, 0L);
    }

    public int poll(int maxEntries, WorldReader reader, long nowNanos, Settler settler, TickBudget budget, int sharePercent) {
        return poll(maxEntries, reader, nowNanos, settler, budget, sharePercent, 0L);
    }

    public int poll(int maxEntries, WorldReader reader, long nowNanos, Settler settler, TickBudget budget, int sharePercent, long maxAgeNanos) {
        int settled = 0;
        int checked = 0;
        int limit = Math.min(maxEntries, order.size());
        while (checked < limit && !order.isEmpty()) {
            if (budget != null && checked > 0 && budget.usedAtLeast(sharePercent)) break;
            if (cursor >= order.size()) cursor = 0;
            Entry entry = order.get(cursor);
            Outcome outcome = reader.poll(entry.token);
            checked++;
            if (outcome == Outcome.PENDING) {
                if (maxAgeNanos > 0L && nowNanos - entry.issueNanos[0] > maxAgeNanos) {
                    watchdogTimeouts += entry.count;
                    settled += settleEntry(entry, Outcome.TIMEOUT, nowNanos, settler);
                    continue;
                }
                cursor++;
                continue;
            }
            settled += settleEntry(entry, outcome, nowNanos, settler);
        }
        return settled;
    }

    public int forceSettle(Outcome outcome, long nowNanos, Settler settler) {
        int settled = 0;
        while (!order.isEmpty()) settled += settleEntry(order.get(order.size() - 1), outcome, nowNanos, settler);
        return settled;
    }

    private int settleEntry(Entry entry, Outcome outcome, long nowNanos, Settler settler) {
        int n = entry.count;
        for (int i = 0; i < n; i++) settler.settle(entry.token, entry.families[i], outcome, entry.issueNanos[i], nowNanos);
        remove(entry);
        waiters -= n;
        return n;
    }

    private void remove(Entry entry) {
        byToken.remove(entry.token);
        int last = order.size() - 1;
        Entry tail = order.get(last);
        order.set(entry.slot, tail);
        tail.slot = entry.slot;
        order.remove(last);
        if (cursor > last) cursor = 0;
    }
}
