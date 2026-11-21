package net.worlddata.benchmark.common.pack;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ThrashPipeline {

    public record Burst(boolean completed, long singleNanos, long parallelNanos, int threads, int readsPerThread, boolean digestsAgree) {
        public double ratio() {
            return completed && parallelNanos > 0L ? (double) singleNanos / parallelNanos : 0.0;
        }

        public long totalReads() {
            return (long) threads * readsPerThread + readsPerThread;
        }
    }

    private final int threads;
    private final ExecutorService pool;
    private int bursts;
    private int failedBursts;
    private double ratioSum;
    private double worstRatio = Double.MAX_VALUE;
    private int disagreements;

    public ThrashPipeline(int threads) {
        this.threads = threads;
        this.pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread t = new Thread(runnable, "worlddata-pack-thrash");
            t.setDaemon(true);
            return t;
        });
    }

    public void warmUp() {
        CountDownLatch started = new CountDownLatch(threads);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        try {
            started.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        release.countDown();
    }

    public Burst burst(ThrashProbe probe, int[] xs, int[] ys, int[] zs, int readsPerThread) {
        long singleStart = System.nanoTime();
        long expected = readLoop(probe, xs, ys, zs, readsPerThread);
        long singleNanos = System.nanoTime() - singleStart;

        CyclicBarrier line = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong firstStart = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lastEnd = new AtomicLong();
        AtomicLong mismatches = new AtomicLong();
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    line.await(5, TimeUnit.SECONDS);
                    long start = System.nanoTime();
                    firstStart.accumulateAndGet(start, Math::min);
                    long digest = readLoop(probe, xs, ys, zs, readsPerThread);
                    long end = System.nanoTime();
                    lastEnd.accumulateAndGet(end, Math::max);
                    if (digest != expected) mismatches.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (BrokenBarrierException | java.util.concurrent.TimeoutException broken) {
                    mismatches.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        boolean finished;
        try {
            finished = done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        bursts++;
        if (!finished || firstStart.get() == Long.MAX_VALUE) {
            failedBursts++;
            return new Burst(false, singleNanos, 0L, threads, readsPerThread, false);
        }
        long parallelNanos = Math.max(1L, lastEnd.get() - firstStart.get());
        boolean agree = mismatches.get() == 0L;
        if (!agree) disagreements++;
        Burst result = new Burst(true, singleNanos, parallelNanos, threads, readsPerThread, agree);
        ratioSum += result.ratio();
        worstRatio = Math.min(worstRatio, result.ratio());
        return result;
    }

    private static long readLoop(ThrashProbe probe, int[] xs, int[] ys, int[] zs, int reads) {
        long digest = 0L;
        int cells = xs.length;
        for (int i = 0; i < reads; i++) {
            int cell = i % cells;
            digest += probe.readOnce(xs[cell], ys[cell], zs[cell]);
        }
        return digest;
    }

    public int bursts() {
        return bursts;
    }

    public int failedBursts() {
        return failedBursts;
    }

    public int disagreements() {
        return disagreements;
    }

    public double meanRatio() {
        int good = bursts - failedBursts;
        return good == 0 ? 0.0 : ratioSum / good;
    }

    public double worstRatio() {
        return worstRatio == Double.MAX_VALUE ? 0.0 : worstRatio;
    }

    public int threads() {
        return threads;
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
