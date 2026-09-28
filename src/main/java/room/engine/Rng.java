package room.engine;

import room.util.Hashes;

/**
 * 确定性 64 位 xorshift 伪随机数发生器。
 * 不使用 java.util.Random（其算法可能随 JDK 版本变化）。
 */
public final class Rng {

    private long state;
    private long draws;

    public Rng(String seed) {
        String h = Hashes.sha256Hex("rng-seed:" + seed);
        this.state = Long.parseUnsignedLong(h.substring(0, 16), 16);
        if (this.state == 0) this.state = 0x9E3779B97F4A7C15L;
        this.draws = 0;
    }

    public Rng(long state, long draws) {
        this.state = state == 0 ? 0x9E3779B97F4A7C15L : state;
        this.draws = draws;
    }

    public long nextLong() {
        state ^= state >>> 12;
        state ^= state << 25;
        state ^= state >>> 27;
        draws++;
        return state * 0x2545F4914F6CDD1DL;
    }

    /** [0,1) 的确定性双精度。 */
    public double nextDouble() {
        return (nextLong() >>> 11) * (1.0 / 9007199254740992.0);
    }

    /** [min, max] 闭区间整数。 */
    public long nextInt(long min, long max) {
        if (max < min) throw new IllegalArgumentException("rand 区间非法: [" + min + "," + max + "]");
        long span = max - min + 1;
        return min + Long.remainderUnsigned(nextLong(), span);
    }

    public long getState() { return state; }
    public long getDraws() { return draws; }
}
