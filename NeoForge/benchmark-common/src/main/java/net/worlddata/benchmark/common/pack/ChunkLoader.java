package net.worlddata.benchmark.common.pack;

public interface ChunkLoader {

    Outcome request(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ);

    Outcome pin(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ);

    Outcome status(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ);

    void release(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ);

    void releaseAll();

    long forceLoadCalls();

    long forceLoadBlocked();

    int uniqueChunks();

    int heldChunks();
}
