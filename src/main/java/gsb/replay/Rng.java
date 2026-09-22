package gsb.replay;

/**
 * 确定性随机数：SplitMix64。状态是单个 long，可快照、可回滚。
 * 绝不使用 System.currentTimeMillis / Math.random 等墙上时钟来源。
 */
public final class Rng {
    private long state;

    public Rng(long seed) {
        this.state = seed;
    }

    public long snapshot() {
        return state;
    }

    public void restore(long s) {
        this.state = s;
    }

    public long nextLong() {
        state += 0x9e3779b97f4a7c15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** 返回 [0, bound) 内的整数，bound 必须为正。 */
    public long nextInt(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("random bound must be positive");
        }
        long r = nextLong() & Long.MAX_VALUE;
        return r % bound;
    }
}
