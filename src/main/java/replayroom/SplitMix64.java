package replayroom;

public final class SplitMix64 {
    public static final long INITIAL_STATE = 0x9e3779b97f4a7c15L;
    private long state;

    public SplitMix64(long seed) {
        this.state = INITIAL_STATE ^ seed;
    }

    public SplitMix64(long state, boolean restore) {
        this.state = state;
    }

    public long nextLong(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("random bound must be positive");
        }
        long candidate;
        do {
            state += 0x9e3779b97f4a7c15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            candidate = z ^ (z >>> 31);
        } while (candidate == Long.MIN_VALUE);
        return Math.abs(candidate) % bound;
    }

    public long getState() {
        return state;
    }
}
