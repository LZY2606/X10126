package replay;

/** Deterministic 64-bit LCG so replay randomness is reproducible and snapshot-able. */
public final class Lcg {
    private long state;

    public Lcg(long seed) { this.state = seed ^ 0x5DEECE66DL; }

    public long state() { return state; }

    public void restore(long state) { this.state = state; }

    public long nextLong() {
        state = state * 6364136223846793005L + 1442695040888963407L;
        return state >>> 1;
    }

    public long nextInt(long bound) {
        if (bound <= 0) throw new IllegalArgumentException("randInt bound must be > 0");
        return Math.floorMod(nextLong(), bound);
    }
}
