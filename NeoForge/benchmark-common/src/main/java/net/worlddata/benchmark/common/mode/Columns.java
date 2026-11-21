package net.worlddata.benchmark.common.mode;

import java.util.Arrays;

public final class Columns {

    private final long[][] data;
    private int size;

    public Columns(int columns) {
        data = new long[columns][];
        for (int i = 0; i < columns; i++) data[i] = new long[1024];
    }

    public int add() {
        if (size == data[0].length) {
            for (int i = 0; i < data.length; i++) data[i] = Arrays.copyOf(data[i], size * 2);
        }
        return size++;
    }

    public void set(int column, int row, long value) {
        data[column][row] = value;
    }

    public long get(int column, int row) {
        return data[column][row];
    }

    public int size() {
        return size;
    }

    public long sum(int column, int from, int to) {
        long sum = 0L;
        for (int i = from; i < to; i++) sum += data[column][i];
        return sum;
    }

    public double[] millis(int column, int from, int to) {
        double[] out = new double[Math.max(0, to - from)];
        for (int i = 0; i < out.length; i++) out[i] = data[column][from + i] / 1_000_000.0;
        return out;
    }
}
