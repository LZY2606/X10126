package replay.core;

/**
 * Logical clock driven purely by processed events. Never sleeps, never reads
 * the wall clock, so replays stay deterministic and tests run instantly.
 */
public final class VirtualClock {
    private long now;

    public VirtualClock(long start) {
        this.now = start;
    }

    public long now() {
        return now;
    }

    public void advanceTo(long time) {
        if (time < now) {
            throw new IllegalArgumentException(
                    "logical clock cannot move backwards: " + time + " < " + now);
        }
        now = time;
    }

    public VirtualClock copy() {
        return new VirtualClock(now);
    }
}
