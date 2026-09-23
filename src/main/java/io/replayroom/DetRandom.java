package io.replayroom;

/**
 * 确定性随机数：splitmix64 播种 + LCG 推进。
 * 状态仅是一个 long，随检查点保存，rand(n) 在 [0,n) 上取模，保证重放一致。
 */
public final class DetRandom {
    private long state;

    public DetRandom(long seed) {
        this.state = mix(seed + 0x9E3779B97F4A7C15L);
    }

    private DetRandom(long state, boolean seeded) {
        this.state = state;
    }

    public static DetRandom fromState(long state) {
        return new DetRandom(state, true);
    }

    public long state() {
        return state;
    }

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 推进一次，返回非负 63 位整数。 */
    public long nextLong() {
        state = state * 6364136223846793005L + 1442695040888963407L;
        return state >>> 1;
    }

    /** 返回 [0, bound) 内的整数；bound 必须为正。 */
    public long nextInt(long bound) {
        if (bound <= 0) throw new IllegalArgumentException("rand 的上界必须为正数");
        return Long.remainderUnsigned(nextLong(), bound);
    }
}
