package replayroom;

/**
 * 与 java.util.Random 相同算法的 48 位 LCG，但状态是普通 long，
 * 可随快照/检查点一起序列化，保证跨进程重放一致。
 */
public final class DetRandom {
    private static final long MULT = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    private long seed;

    public DetRandom(long seed) {
        this.seed = (seed ^ MULT) & MASK;
    }

    public DetRandom copy() {
        DetRandom r = new DetRandom(0);
        r.seed = this.seed;
        return r;
    }

    public long state() { return seed; }
    public void restoreState(long s) { this.seed = s & MASK; }

    private int next(int bits) {
        seed = (seed * MULT + ADDEND) & MASK;
        return (int) (seed >>> (48 - bits));
    }

    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        int r = next(31);
        int m = bound - 1;
        if ((bound & m) == 0) {
            r = (int) ((bound * (long) r) >> 31);
        } else {
            int bits, v;
            do {
                bits = next(31);
                v = bits % bound;
            } while (bits - v + m < 0);
            r = v;
        }
        return r;
    }

    public double nextDouble() {
        return (((long) next(26) << 27) + next(27)) / (double) (1L << 53);
    }
}
