package com.replayroom.core;

/** Splittable-state LCG; state is exposed so transactions/checkpoints can save and restore it. */
public final class DeterministicRandom {
    private long state;

    public DeterministicRandom(long seed) {
        this.state = seed;
    }

    public long state() {
        return state;
    }

    public void restore(long savedState) {
        this.state = savedState;
    }

    public long nextLong() {
        state = state * 6364136223846793005L + 1442695040888963407L;
        return state;
    }

    /** Uniform double in [0, 1). */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Uniform long in [0, bound). */
    public long nextLong(long bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        long bits = nextLong() >>> 1;
        return bits % bound;
    }
}
