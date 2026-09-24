package replay;

public final class DeterministicRandom {
    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;
    private static final long MASK = (1L << 48) - 1;

    private long state;

    public DeterministicRandom(long seed) {
        this.state = seed & MASK;
    }

    public long stateValue() {
        return state;
    }

    public void restore(long value) {
        this.state = value & MASK;
    }

    public long nextLong(long minInclusive, long maxInclusive) {
        if (maxInclusive < minInclusive) {
            throw new EngineException("random range is invalid");
        }
        advance();
        long range = maxInclusive - minInclusive + 1;
        long value = Long.remainderUnsigned(state, range);
        return minInclusive + value;
    }

    private void advance() {
        state = (state * MULTIPLIER + INCREMENT) & MASK;
    }
}
