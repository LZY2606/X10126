package replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Full restorable state of a branch at a given point in the trace. */
public final class Snapshot {
    public String state;
    public Map<String, Object> vars;
    public long logicalTime;
    public long rngState;
    public long internalSeq;
    public List<Event> internalQueue;
    public List<Event> pendingExternal;

    public Snapshot(String state, Map<String, Object> vars, long logicalTime, long rngState,
                    long internalSeq, List<Event> internalQueue, List<Event> pendingExternal) {
        this.state = state;
        this.vars = vars;
        this.logicalTime = logicalTime;
        this.rngState = rngState;
        this.internalSeq = internalSeq;
        this.internalQueue = internalQueue;
        this.pendingExternal = pendingExternal;
    }

    @SuppressWarnings("unchecked")
    public Snapshot copy() {
        return new Snapshot(
                state,
                (Map<String, Object>) Json.deepCopy(vars),
                logicalTime,
                rngState,
                internalSeq,
                new ArrayList<>(internalQueue),
                new ArrayList<>(pendingExternal));
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = Json.map();
        m.put("state", state);
        m.put("vars", vars);
        m.put("logicalTime", logicalTime);
        m.put("rngState", rngState);
        m.put("internalSeq", internalSeq);
        List<Object> iq = Json.list();
        for (Event e : internalQueue) iq.add(e.toJson());
        m.put("internalQueue", iq);
        List<Object> pe = Json.list();
        for (Event e : pendingExternal) pe.add(e.toJson());
        m.put("pendingExternal", pe);
        return m;
    }

    public static Snapshot fromJson(Map<String, Object> json) {
        List<Event> iq = new ArrayList<>();
        for (Object o : Json.asList(json.get("internalQueue"), "snapshot.internalQueue")) {
            iq.add(Event.fromStoredJson(Json.asMap(o, "event")));
        }
        List<Event> pe = new ArrayList<>();
        for (Object o : Json.asList(json.get("pendingExternal"), "snapshot.pendingExternal")) {
            pe.add(Event.fromStoredJson(Json.asMap(o, "event")));
        }
        return new Snapshot(
                Json.asString(json.get("state"), "snapshot.state"),
                Json.asMap(json.get("vars"), "snapshot.vars"),
                Json.asLong(json.get("logicalTime"), "snapshot.logicalTime"),
                Json.asLong(json.get("rngState"), "snapshot.rngState"),
                Json.asLong(json.get("internalSeq"), "snapshot.internalSeq"),
                iq, pe);
    }
}
