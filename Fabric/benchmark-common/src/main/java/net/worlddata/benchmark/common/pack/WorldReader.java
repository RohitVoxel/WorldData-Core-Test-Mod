package net.worlddata.benchmark.common.pack;

import java.util.Map;

public interface WorldReader {

    Arm arm();

    String dimension();

    Outcome read(ReadAction action, int x, int y, int z, int extra);

    long lastDigest();

    Outcome poll(long token);

    long chunkToken(int chunkX, int chunkZ);

    String blockIdAt(int x, int y, int z);

    ThrashProbe openThrash(int chunkX, int chunkZ);

    double visitAllocationBytesPerCall(int chunkX, int chunkZ, int calls);

    Map<String, Object> info();
}
