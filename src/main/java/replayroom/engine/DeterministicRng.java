package replayroom.engine;

/**
 * Deterministic 64-bit linear congruential generator (Knuth/MMIX constants).
 * State is a plain long so it can be snapshotted and rolled back.
 */
public final class DeterministicRng {

    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;

    private long state;

    public DeterministicRng(long seed) {
        this.state = mix(seed);
    }

    private DeterministicRng(long state, boolean mixed) {
        this.state = state;
    }

    public static DeterministicRng fromState(long state) {
        return new DeterministicRng(state, true);
    }

    public long state() {
        return state;
    }

    public void restore(long savedState) {
        this.state = savedState;
    }

    private static long mix(long seed) {
        long z = seed ^ MULTIPLIER;
        for (int i = 0; i < 4; i++) {
            z = z * MULTIPLIER + INCREMENT;
        }
        return z | 1L;
    }

    public long nextLong() {
        state = state * MULTIPLIER + INCREMENT;
        return state;
    }

    /** Uniform value in [0, 1). */
    public double nextUnit() {
        long bits = nextLong() >>> 11;
        return bits / (double) (1L << 53);
    }
}
