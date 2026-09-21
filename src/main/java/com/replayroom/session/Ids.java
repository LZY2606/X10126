package com.replayroom.session;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic friendly ids. Identity does not participate in trajectory
 * hashes, but stable counters keep on-disk files and UI output readable.
 */
public final class Ids {

    private static final AtomicLong SESSION_COUNTER = new AtomicLong();
    private static final AtomicLong BRANCH_COUNTER = new AtomicLong();
    private static final AtomicLong CHECKPOINT_COUNTER = new AtomicLong();

    private Ids() {
    }

    public static String session() {
        return "s_" + Long.toString(SESSION_COUNTER.incrementAndGet(), 36)
                + "_" + Long.toHexString(System.nanoTime() & 0xffffffL);
    }

    public static String branch() {
        return "b_" + Long.toString(BRANCH_COUNTER.incrementAndGet(), 36)
                + "_" + Long.toHexString(System.nanoTime() & 0xffffffL);
    }

    public static String checkpoint() {
        return "c_" + Long.toString(CHECKPOINT_COUNTER.incrementAndGet(), 36)
                + "_" + Long.toHexString(System.nanoTime() & 0xffffffL);
    }
}
