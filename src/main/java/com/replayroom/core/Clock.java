package com.replayroom.core;

/**
 * Wall-clock abstraction. Used only for metadata timestamps (createdAt etc.);
 * never feeds into fingerprints or trace hashes, so replays stay deterministic.
 * Tests use a virtual clock and never sleep.
 */
public interface Clock {
    long nowMillis();

    static Clock system() {
        return System::currentTimeMillis;
    }

    /** Manually advanced clock for tests. */
    final class Virtual implements Clock {
        private long now;

        public Virtual(long start) { this.now = start; }

        public void advance(long millis) { now += millis; }

        @Override
        public long nowMillis() { return now; }
    }
}
