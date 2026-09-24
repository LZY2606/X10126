package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Plain data model (Jackson-friendly) for the replay room. */
public final class Model {

    private Model() {}

    public static class Definition {
        public String name = "untitled";
        public String version = "1";
        public String initialState = "IDLE";
        public long seed = 0;
        public List<String> states = new ArrayList<>();
        /** source name -> priority, lower value arrives first at equal logical time. */
        public Map<String, Integer> sourcePriority = new LinkedHashMap<>();
        public List<Transition> transitions = new ArrayList<>();
    }

    public static class Transition {
        public String event;
        /** null or empty means "from any state". */
        public List<String> from;
        /** null means "stay in current state". */
        public String to;
        /** expression evaluated against the pre-event snapshot; null means always. */
        public String condition;
        public List<Map<String, Object>> actions = new ArrayList<>();

        public String describe() {
            return event + ": " + (from == null || from.isEmpty() ? "*" : String.join("|", from))
                    + " -> " + (to == null ? "(stay)" : to);
        }
    }

    /** An external or internal event, ordered by (time, source priority, seq). */
    public static class Ev {
        public long time;
        public String source = "";
        public long seq;
        public String name;
        public Map<String, Object> payload;

        public Ev() {}

        public Ev(long time, String source, long seq, String name, Map<String, Object> payload) {
            this.time = time;
            this.source = source;
            this.seq = seq;
            this.name = name;
            this.payload = payload;
        }

        public boolean sameIdentity(Ev other) {
            return time == other.time && seq == other.seq
                    && strEq(source, other.source) && strEq(name, other.name);
        }

        static boolean strEq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        public String label() {
            return name + "@t=" + time + " src=" + source + "#" + seq;
        }
    }

    /** A full deterministic snapshot: state machine state + variables + RNG state. */
    public static class Snapshot {
        public String state;
        public Map<String, Object> vars = new TreeMap<>();
        public long rng;

        public Snapshot copy() {
            Snapshot s = new Snapshot();
            s.state = state;
            s.vars = new TreeMap<>(vars);
            s.rng = rng;
            return s;
        }
    }

    /** One recorded replay step (part of the trace). */
    public static class StepRec {
        public int index;
        public Ev event;
        public boolean internal;
        /** transition description, null when no transition matched. */
        public String transition;
        public Snapshot before;
        public Snapshot after;
        public List<String> outputs = new ArrayList<>();
        public boolean failed;
        public String error;
    }

    public static class Checkpoint {
        public String id;
        public String name;
        public String lineId;
        /** trace length on the line when the checkpoint was taken. */
        public int stepIndex;
        /** external queue cursor on the line when the checkpoint was taken. */
        public int cursor;
        public long internalSeq;
        public Snapshot snapshot;
        public String defFingerprint;
    }

    /** One replay line: the main line or a branch forked from a checkpoint. */
    public static class Line {
        public String id;
        public String name;
        /** fork point: line id + step index on that line; null for the main line. */
        public String baseLineId;
        public int baseStepIndex;
        public List<Ev> queue = new ArrayList<>();
        public int cursor;
        public long internalSeq;
        public List<Ev> internalQueue = new ArrayList<>();
        public Snapshot snapshot = new Snapshot();
        public List<StepRec> trace = new ArrayList<>();

        public boolean finished() {
            return internalQueue.isEmpty() && cursor >= queue.size();
        }
    }

    public static class Session {
        public String id;
        public String name;
        public Definition definition;
        public String defFingerprint;
        public String sessionFingerprint;
        public List<Ev> events = new ArrayList<>();
        public Map<String, Line> lines = new LinkedHashMap<>();
        public List<Checkpoint> checkpoints = new ArrayList<>();
        public String activeLineId = "main";

        public Line main() { return lines.get("main"); }

        public Line line(String id) {
            Line line = lines.get(id);
            if (line == null) throw new ApiException(404, "no such line: " + id);
            return line;
        }
    }

    public static class ApiException extends RuntimeException {
        public final int status;
        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** Raised when a checkpoint belongs to another definition version. */
    public static class CheckpointVersionException extends RuntimeException {
        public final String expected;
        public final String actual;
        public CheckpointVersionException(String expected, String actual) {
            super("checkpoint definition fingerprint mismatch: checkpoint=" + actual + " current=" + expected);
            this.expected = expected;
            this.actual = actual;
        }
    }

    /** Raised when an action fails; state changes and derived events roll back. */
    public static class ActionFailure extends RuntimeException {
        public ActionFailure(String message) { super(message); }
    }
}
