package com.replayroom.core;

/**
 * Deterministic, serializable PRNG (64-bit LCG, Knuth constants). The whole
 * state is a single long so it can live inside the state snapshot and be
 * rolled back together with variables when an action fails.
 */
public class LcgRandom {
    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;

    private long state;

    public LcgRandom(long seed) {
        this.state = seed;
    }

    public long state() {
        return state;
    }

    public void state(long newState) {
        this.state = newState;
    }

    public long nextLong() {
        state = state * MULTIPLIER + INCREMENT;
        return state >>> 1;
    }

    /** Uniform value in [0, bound). */
    public long nextLong(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        long bits = nextLong();
        long limit = Long.MAX_VALUE - (Long.MAX_VALUE % bound);
        while (bits >= limit) {
            bits = nextLong();
        }
        return bits % bound;
    }

    public LcgRandom copy() {
        return new LcgRandom(state);
    }
}
