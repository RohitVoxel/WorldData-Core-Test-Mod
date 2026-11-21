package net.worlddata.benchmark.common.pack;

public interface WorldWriter {

    Outcome write(int x, int y, int z, BlockCode code);

    String blockId(int x, int y, int z);

    int restoreAll();

    int journalSize();
}
