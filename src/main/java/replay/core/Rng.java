package replay.core;

/**
 * Deterministic 64-bit counter-based generator (PCG-style LCG).
 * The full generator state lives inside the machine snapshot, so forking a
 * checkpoint forks the random stream as well. No wall clock is ever used.
 */
public final class Rng {
    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;

    private long state;

    public Rng(long seed) {
        this.state = mix(seed);
    }

    public static Rng fromState(long state) {
        Rng rng = new Rng(0);
        rng.state = state;
        return rng;
    }

    public long state() { return state; }

    private long nextRaw() {
        state = state * MULTIPLIER + INCREMENT;
        long xorshifted = ((state >>> 18) ^ state) >>> 27;
        int rot = (int) (state >>> 59);
        return (xorshifted >>> rot) | (xorshifted << ((-rot) & 31));
    }

    public long nextLong() { return nextRaw(); }

    /** Uniform int in [0, bound). bound must be positive. */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        return (int) Math.floorMod(nextRaw(), (long) bound);
    }

    /** Uniform double in [0, 1). */
    public double nextDouble() {
        return (nextRaw() >>> 11) * 0x1.0p-53;
    }

    private static long mix(long seed) {
        long z = seed + INCREMENT;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
