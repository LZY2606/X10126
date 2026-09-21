package com.replayroom.engine;

/**
 * Deterministic 64-bit LCG (Knuth/MMIX constants). The state is a single
 * long, captured and restored when an action transaction rolls back, so
 * failed steps leave the random stream untouched.
 */
public final class Rng {

    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;

    private long state;

    public Rng(long seed) {
        this.state = seed;
    }

    public long state() {
        return state;
    }

    public void restore(long savedState) {
        this.state = savedState;
    }

    /** Returns the next raw unsigned 64-bit value in [0, 2^64-1]. */
    public long nextLong() {
        state = state * MULTIPLIER + INCREMENT;
        return state;
    }

    /** Uniform integer in [0, bound). */
    public long nextBounded(long bound) {
        if (bound <= 0) {
            throw new EvalException("rand bound must be a positive integer");
        }
        long value = nextLong() & Long.MAX_VALUE;
        return value % bound;
    }

    /** Uniform double in [0, 1). */
    public double nextUnit() {
        long value = nextLong() >>> 11;
        return value / (double) (1L << 53);
    }
}
