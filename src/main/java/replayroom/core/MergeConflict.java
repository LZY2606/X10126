package replayroom.core;

import java.util.List;

/** 分支合并被拒绝：携带第一组冲突事件。 */
public class MergeConflict extends RuntimeException {
    public final Conflict first;
    public final int conflictCount;

    public MergeConflict(Conflict first, int conflictCount) {
        super("合并被拒绝，第一组冲突: " + first.reason
                + " [A=" + first.a.id + " " + first.a.event + " @t=" + first.a.time
                + " src=" + first.a.source + " seq=" + first.a.seq
                + " | B=" + first.b.id + " " + first.b.event + " @t=" + first.b.time
                + " src=" + first.b.source + " seq=" + first.b.seq + "]");
        this.first = first;
        this.conflictCount = conflictCount;
    }

    public static class Conflict {
        public final String reason;
        public final EventInstance a;
        public final EventInstance b;

        public Conflict(String reason, EventInstance a, EventInstance b) {
            this.reason = reason;
            this.a = a;
            this.b = b;
        }
    }
}
