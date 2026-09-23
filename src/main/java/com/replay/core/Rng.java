package com.replay.core;

/** Deterministic SplitMix64 RNG with a fully serializable long state. */
public class Rng {
    private long state;

    public Rng(long seed) {
        this.state = seed;
    }

    public long getState() { return state; }
    public void setState(long state) { this.state = state; }

    public long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Inclusive min, exclusive max. */
    public long nextLong(long min, long max) {
        if (max <= min) return min;
        long bound = max - min;
        long r = nextLong() >>> 1;
        return min + (r % bound);
    }
}
