package replayroom;

public final class DeterministicRandom {
    private static final long MULTIPLIER = 6364136223846793005L;
    private static final long INCREMENT = 1442695040888963407L;

    private DeterministicRandom() {}

    public static long next(long[] state) {
        state[0] = state[0] * MULTIPLIER + INCREMENT;
        return state[0] >>> 1;
    }
}
