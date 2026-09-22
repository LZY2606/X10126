package replay;

/** Deterministic SplitMix64 PRNG; state is a single long, trivially serializable. */
public final class Rng {
    private long state;

    public Rng(long seed) {
        this.state = seed;
    }

    public long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public long nextLong(long bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        long r = nextLong() >>> 1;
        return r % bound;
    }

    public long state() { return state; }
    public void state(long s) { this.state = s; }
}
