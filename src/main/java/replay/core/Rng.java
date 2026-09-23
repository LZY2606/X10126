package replay.core;

/** SplitMix64：状态可完整序列化，保证检查点/分叉/恢复后随机序列一致。 */
public final class Rng {
    private long state;

    public Rng(long seed) { this.state = seed; }

    public long state() { return state; }

    public void state(long s) { this.state = s; }

    public long next() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public long nextRand(long bound) {
        return Math.floorMod(next(), bound);
    }
}
