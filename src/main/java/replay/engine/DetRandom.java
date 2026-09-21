package replay.engine;

/**
 * Deterministic pseudo-random source locked to the session seed.
 * 64-bit LCG (Knuth MMIX constants). Bounded draws use Lemire's method.
 * State is fully reproducible from (seed, callCount).
 */
public final class DetRandom {

    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;
    private static final long MASK = (1L << 63);

    private final long seed;
    private long state;
    private long callCount;

    public DetRandom(long seed) {
        this.seed = seed;
        this.state = seed;
        this.callCount = 0;
    }

    public long nextLong() {
        state = state * MULTIPLIER + INCREMENT;
        callCount++;
        return state ^ (state >>> 33);
    }

    public long nextLong(long boundExclusive) {
        long raw = nextLong() & Long.MAX_VALUE;
        long value = raw % boundExclusive;
        return value;
    }

    public long getSeed() {
        return seed;
    }

    public long getCallCount() {
        return callCount;
    }

    /** Restore exact position by replaying calls (cheap; virtual, no clock). */
    public static DetRandom restore(long seed, long callCount) {
        DetRandom r = new DetRandom(seed);
        for (long i = 0; i < callCount; i++) {
            r.nextLong();
        }
        return r;
    }
}
