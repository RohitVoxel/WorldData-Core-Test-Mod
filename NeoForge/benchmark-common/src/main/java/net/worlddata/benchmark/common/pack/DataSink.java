package net.worlddata.benchmark.common.pack;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

public final class DataSink {

    private final AtomicLongArray ring;
    private final int mask;
    private final AtomicLong head = new AtomicLong();
    private final AtomicLong tail = new AtomicLong();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder flushed = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder bytes = new LongAdder();
    private volatile boolean running;
    private volatile boolean ioFailed;
    private Thread thread;
    private OutputStream out;

    public DataSink(int ringSize) {
        int size = Integer.highestOneBit(Math.max(16, ringSize - 1)) << 1;
        this.ring = new AtomicLongArray(size);
        this.mask = size - 1;
    }

    public void start(Path file) {
        try {
            Files.createDirectories(file.getParent());
            out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
        } catch (IOException e) {
            ioFailed = true;
        }
        running = true;
        thread = new Thread(this::drainLoop, "worlddata-pack-sink");
        thread.setDaemon(true);
        thread.start();
    }

    public void accept(long digest) {
        accepted.increment();
        for (int attempt = 0; attempt < 3; attempt++) {
            long h = head.get();
            if (h - tail.get() >= ring.length()) {
                dropped.increment();
                return;
            }
            if (head.compareAndSet(h, h + 1)) {
                ring.set((int) (h & mask), digest | 1L);
                return;
            }
        }
        dropped.increment();
    }

    public void close() {
        running = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(5000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        drainSlice(Long.MAX_VALUE);
        closeOut();
    }

    private void drainLoop() {
        while (running || tail.get() < head.get()) {
            long taken = drainSlice(PackConfig.SINK_BUDGET_BYTES_PER_SLICE);
            if (taken == 0L) sleep(2L);
            else sleep(1L);
        }
        closeOut();
    }

    private synchronized long drainSlice(long budgetBytes) {
        long used = 0L;
        while (used < budgetBytes) {
            long t = tail.get();
            if (t >= head.get()) break;
            int index = (int) (t & mask);
            long value = ring.get(index);
            if (value == 0L) break;
            ring.set(index, 0L);
            tail.set(t + 1);
            writeLong(value);
            flushed.increment();
            used += 8L;
        }
        return used;
    }

    private void writeLong(long value) {
        if (out == null || ioFailed) return;
        try {
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (value >>> shift) & 0xFF);
            bytes.add(8L);
        } catch (IOException e) {
            ioFailed = true;
        }
    }

    private synchronized void closeOut() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException e) {
            ioFailed = true;
        }
        out = null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public long accepted() {
        return accepted.sum();
    }

    public long flushed() {
        return flushed.sum();
    }

    public long dropped() {
        return dropped.sum();
    }

    public long bytes() {
        return bytes.sum();
    }

    public boolean ioFailed() {
        return ioFailed;
    }
}
