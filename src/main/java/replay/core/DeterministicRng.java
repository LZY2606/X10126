package replay.core;

/**
 * 确定性伪随机数：与 java.util.Random 的 48 位 LCG 相同的公式。
 * 持久化“已消耗次数”，重建时从 (seed, uses) 推进，保证任意检查点后序列一致。
 */
public final class DeterministicRng {

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    private final long seed;
    private long state;
    private long uses;

    public DeterministicRng(long seed, long uses) {
        this.seed = seed;
        this.state = seed ^ MULTIPLIER;
        this.uses = 0;
        for (long i = 0; i < uses; i++) {
            advance();
        }
        this.uses = uses;
    }

    public long seed() {
        return seed;
    }

    public long uses() {
        return uses;
    }

    /** 返回 [min, max] 闭区间内的整数（语义同 java.util.Random.nextInt(bound)）。 */
    public int nextIntInclusive(int min, int max) {
        if (max < min) {
            throw new IllegalArgumentException("random: max (" + max + ") < min (" + min + ")");
        }
        int bound = max - min + 1;
        int value;
        if ((bound & -bound) == bound) {
            value = (int) ((bound * (long) next(31)) >> 31);
        } else {
            int bits;
            int candidate;
            do {
                bits = next(31);
                candidate = bits % bound;
            } while (bits - candidate + (bound - 1) < 0);
            value = candidate;
        }
        return min + value;
    }

    private int next(int bits) {
        advance();
        return (int) (state >>> (48 - bits));
    }

    private void advance() {
        state = (state * MULTIPLIER + ADDEND) & MASK;
        uses++;
    }
}
