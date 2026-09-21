package com.replayroom.session;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual wall-clock substitute used for bookkeeping timestamps. It is a
 * process-local monotonic counter and never sleeps; replay semantics depend
 * only on event logical times, not on these values.
 */
public final class LogicalClock {

    private static final AtomicLong COUNTER = new AtomicLong();

    private LogicalClock() {
    }

    public static String now() {
        return "t+" + COUNTER.incrementAndGet();
    }
}
