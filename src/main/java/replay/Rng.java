package replay;

/**
 * Deterministic 64-bit linear congruential generator (Knuth constants).
 * The sequence depends only on the initial seed, so every replay with the
 * locked seed produces identical "random" draws. State is serialized as a
 * long into each step so resumes continue the same stream.
 */
public final class Rng {

    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;

    private long state;

    public Rng(long seed) {
        this.state = seed;
    }

    public long stateValue() {
        return state;
    }

    public long nextLong() {
        state = state * MULTIPLIER + INCREMENT;
        return state;
    }

    /** Uniform long in [0, bound). */
    public long nextLong(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        long value = nextLong() >>> 1;
        return value % bound;
    }

    /** Uniform double in [0, 1). */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Uniform long in [min, max] inclusive. */
    public long range(long min, long max) {
        if (max < min) {
            throw new IllegalArgumentException("max must be >= min");
        }
        long span = max - min + 1;
        return min + nextLong(span);
    }
}
