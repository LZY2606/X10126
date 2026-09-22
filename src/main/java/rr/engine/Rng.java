package rr.engine;

/** SplitMix64 确定性伪随机；状态随快照保存、随失败回滚。 */
public final class Rng {
    private long state;

    public Rng(long seed) { this.state = mix(seed + 0x9E3779B97F4A7C15L); }

    private Rng(long state, boolean internal) { this.state = state; }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        return mix(state);
    }

    /** [min, max) 区间整数。 */
    public long range(long min, long max) {
        long span = max - min;
        long r = Long.remainUnsigned(nextLong(), span);
        return min + r;
    }

    public long getState() { return state; }
    public static Rng fromState(long state) { return new Rng(state, true); }
}
