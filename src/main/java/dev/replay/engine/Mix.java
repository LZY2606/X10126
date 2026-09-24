package dev.replay.engine;

/**
 * Deterministic 64-bit generator used for both the RNG seed scramble and the
 * per-draw step. State advances by a fixed LCG; the emitted value is a splitmix
 * avalanche of that state, so a locked seed yields identical draws across
 * machines and JVM runs.
 */
public final class Mix {
    private static final long LCG_ADD = 0x9E3779B97F4A7C15L;

    private Mix() {
    }

    public static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }

    public static long advance(long state) {
        return state * 6364136223846793005L + LCG_ADD;
    }

    public static long toLong(long state) {
        return mix(state);
    }
}
