package net.worlddata.benchmark.common.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

public final class BenchPositions {

    public static final int FIXED_POSITIONS = 64;
    public static final int RANDOM_POOL = 1 << 16;
    public static final int ARENA_RADIUS = 32;
    public static final int ARENA_HALF_HEIGHT = 16;
    public static final long SEED = 0x57A7E1D5L;

    public record Result(BenchPositions positions, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private final int[] xs;
    private final int[] ys;
    private final int[] zs;
    private final int mask;
    private final int minChunkX;
    private final int minChunkZ;
    private final int chunkWidth;
    private final int chunkDepth;
    private final int[] chunkXs;
    private final int[] chunkZs;
    private final int arenaChunks;
    private final String description;

    private BenchPositions(int[] xs, int[] ys, int[] zs, int minChunkX, int minChunkZ, int chunkWidth, int chunkDepth,
                           int[] chunkXs, int[] chunkZs, int arenaChunks, String description) {
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.mask = xs.length - 1;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunkWidth = chunkWidth;
        this.chunkDepth = chunkDepth;
        this.chunkXs = chunkXs;
        this.chunkZs = chunkZs;
        this.arenaChunks = arenaChunks;
        this.description = description;
    }

    public int mask() {
        return mask;
    }

    public int size() {
        return xs.length;
    }

    public int x(int index) {
        return xs[index];
    }

    public int y(int index) {
        return ys[index];
    }

    public int z(int index) {
        return zs[index];
    }

    public int slot(int x, int z) {
        return ((x >> 4) - minChunkX) * chunkDepth + ((z >> 4) - minChunkZ);
    }

    public int slots() {
        return chunkWidth * chunkDepth;
    }

    public int[] chunkXs() {
        return chunkXs.clone();
    }

    public int[] chunkZs() {
        return chunkZs.clone();
    }

    public int usableChunks() {
        return chunkXs.length;
    }

    public int arenaChunks() {
        return arenaChunks;
    }

    public int skippedChunks() {
        return arenaChunks - chunkXs.length;
    }

    public String description() {
        return description;
    }

    public static Result build(BenchMode mode, int playerX, int playerY, int playerZ, int minY, int maxY, ChunkProbe probe) {
        int topY = maxY - 1;
        if (topY < minY) return fail("The world has no build height to read from");
        return mode == BenchMode.FIXED
                ? fixed(playerX, playerY, playerZ, minY, topY, probe)
                : arena(playerX, playerY, playerZ, minY, topY, probe);
    }

    private static Result fixed(int playerX, int playerY, int playerZ, int minY, int topY, ChunkProbe probe) {
        int chunkX = playerX >> 4;
        int chunkZ = playerZ >> 4;
        String problem = probe.problem(chunkX, chunkZ);
        if (problem != null) {
            return fail("The chunk you are standing in cannot be benchmarked: " + problem
                    + ". Stand in an area WorldData Core already holds as LIVE and try again");
        }
        int[] xs = new int[FIXED_POSITIONS];
        int[] ys = new int[FIXED_POSITIONS];
        int[] zs = new int[FIXED_POSITIONS];
        int localX = playerX & 15;
        int localZ = playerZ & 15;
        for (int i = 0; i < FIXED_POSITIONS; i++) {
            xs[i] = (chunkX << 4) + ((localX + (i & 3)) & 15);
            zs[i] = (chunkZ << 4) + ((localZ + ((i >> 2) & 3)) & 15);
            ys[i] = clamp(playerY + ((i >> 4) & 3) - 1, minY, topY);
        }
        String text = FIXED_POSITIONS + " fixed positions around the player block in chunk " + chunkX + "," + chunkZ;
        return new Result(new BenchPositions(xs, ys, zs, chunkX, chunkZ, 1, 1,
                new int[]{chunkX}, new int[]{chunkZ}, 1, text), null);
    }

    private static Result arena(int playerX, int playerY, int playerZ, int minY, int topY, ChunkProbe probe) {
        int minChunkX = (playerX - ARENA_RADIUS) >> 4;
        int maxChunkX = (playerX + ARENA_RADIUS) >> 4;
        int minChunkZ = (playerZ - ARENA_RADIUS) >> 4;
        int maxChunkZ = (playerZ + ARENA_RADIUS) >> 4;
        int width = maxChunkX - minChunkX + 1;
        int depth = maxChunkZ - minChunkZ + 1;
        List<int[]> usable = new ArrayList<>();
        String firstProblem = null;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                String problem = probe.problem(cx, cz);
                if (problem == null) usable.add(new int[]{cx, cz});
                else if (firstProblem == null) firstProblem = problem;
            }
        }
        int arenaChunks = width * depth;
        if (usable.isEmpty()) {
            return fail("None of the " + arenaChunks + " chunks within " + ARENA_RADIUS
                    + " blocks of you is LIVE in WorldData Core and loaded in Minecraft (first problem: " + firstProblem + ")");
        }
        int[] xs = new int[RANDOM_POOL];
        int[] ys = new int[RANDOM_POOL];
        int[] zs = new int[RANDOM_POOL];
        SplittableRandom random = new SplittableRandom(SEED);
        int low = clamp(playerY - ARENA_HALF_HEIGHT, minY, topY);
        int high = clamp(playerY + ARENA_HALF_HEIGHT, minY, topY);
        for (int i = 0; i < RANDOM_POOL; i++) {
            int[] chunk = usable.get(random.nextInt(usable.size()));
            xs[i] = (chunk[0] << 4) + random.nextInt(16);
            zs[i] = (chunk[1] << 4) + random.nextInt(16);
            ys[i] = low + random.nextInt(high - low + 1);
        }
        int[] chunkXs = new int[usable.size()];
        int[] chunkZs = new int[usable.size()];
        for (int i = 0; i < chunkXs.length; i++) {
            chunkXs[i] = usable.get(i)[0];
            chunkZs[i] = usable.get(i)[1];
        }
        String text = "random positions across " + usable.size() + " of " + arenaChunks + " chunks within "
                + ARENA_RADIUS + " blocks of the player, y within " + ARENA_HALF_HEIGHT + " of the player, seed " + SEED
                + (usable.size() < arenaChunks ? ", " + (arenaChunks - usable.size()) + " chunks skipped because they are not LIVE or not loaded" : "");
        return new Result(new BenchPositions(xs, ys, zs, minChunkX, minChunkZ, width, depth, chunkXs, chunkZs, arenaChunks, text), null);
    }

    private static Result fail(String message) {
        return new Result(null, message);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
