package io.example.replay;

public final class DeterministicRandom {
    private long seed;
    private long draws;

    public DeterministicRandom(long seed) {
        this.seed = seed;
    }

    private DeterministicRandom(long seed, long draws) {
        this.seed = seed;
        this.draws = draws;
    }

    public double nextUnit() {
        draws++;
        seed = (seed * 6364136223846793005L + 1442695040888963407L + draws);
        return ((seed >>> 11) / (double) (1L << 53));
    }

    public long nextInt(long minInclusive, long maxInclusive) {
        if (maxInclusive < minInclusive) {
            throw new IllegalArgumentException("randomInt max must be >= min");
        }
        long range = maxInclusive - minInclusive + 1L;
        return minInclusive + (long) Math.floor(nextUnit() * range);
    }

    public long seed() {
        return seed;
    }

    public long draws() {
        return draws;
    }

    public DeterministicRandom copy() {
        return new DeterministicRandom(seed, draws);
    }

    public static DeterministicRandom fromState(long seed, long draws) {
        return new DeterministicRandom(seed, draws);
    }
}
