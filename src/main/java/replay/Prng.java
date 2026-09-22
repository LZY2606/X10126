package replay;

/** Deterministic xorshift64* PRNG; state is a single long so it can be snapshotted. */
public final class Prng {
    private Prng() {}

    public static long next(long state) {
        long x = state;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        return x;
    }

    public static long value(long state) {
        return next(state) * 0x2545F4914F6CDD1DL;
    }

    /** Draw a value in [min, max] inclusive; stateHolder[0] advances. */
    public static long draw(long[] stateHolder, long min, long max) {
        long state = stateHolder[0];
        if (state == 0) state = 0x9E3779B97F4A7C15L;
        long value = value(state);
        stateHolder[0] = next(state);
        long range = max - min + 1;
        long pick = Math.floorMod(value, range);
        return min + pick;
    }
}
