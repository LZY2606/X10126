package replay;

/** Virtual logical clock: time only advances when the engine processes an event. Never wall-clock based. */
public final class VirtualClock {
    private long now;

    public VirtualClock() { this(0); }
    public VirtualClock(long start) { this.now = start; }

    public long now() { return now; }

    public void advanceTo(long t) {
        if (t > now) now = t;
    }
}
