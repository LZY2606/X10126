package com.replayroom.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A restorable snapshot. Carries the definition fingerprint so a checkpoint taken
 * under an old definition can never be attached to a session running a new one.
 */
public final class Checkpoint {
    public final String id;
    public final String label;
    public final String defFingerprint;
    public final int cursor;
    public final String state;
    public final Map<String, Object> vars;
    public final List<Event> pending;
    public final int traceLength;
    public final int appliedLength;
    public final long rngState;
    public final long internalSeq;
    public final String traceHash;

    public Checkpoint(String id, String label, String defFingerprint, int cursor, String state,
                      Map<String, Object> vars, List<Event> pending, int traceLength, int appliedLength,
                      long rngState, long internalSeq, String traceHash) {
        this.id = id;
        this.label = label;
        this.defFingerprint = defFingerprint;
        this.cursor = cursor;
        this.state = state;
        this.vars = vars;
        this.pending = pending;
        this.traceLength = traceLength;
        this.appliedLength = appliedLength;
        this.rngState = rngState;
        this.internalSeq = internalSeq;
        this.traceHash = traceHash;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("defFingerprint", defFingerprint);
        m.put("cursor", cursor);
        m.put("state", state);
        m.put("vars", vars);
        List<Object> p = new ArrayList<>();
        for (Event e : pending) p.add(e.toMap());
        m.put("pending", p);
        m.put("traceLength", traceLength);
        m.put("appliedLength", appliedLength);
        m.put("rngState", rngState);
        m.put("internalSeq", internalSeq);
        m.put("traceHash", traceHash);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Checkpoint fromMap(Map<String, Object> m) {
        List<Event> pending = new ArrayList<>();
        Object p = m.get("pending");
        if (p instanceof List) {
            for (Object e : (List<Object>) p) pending.add(Event.fromMap((Map<String, Object>) e));
        }
        return new Checkpoint(
                String.valueOf(m.get("id")),
                String.valueOf(m.get("label")),
                String.valueOf(m.get("defFingerprint")),
                ((Number) m.get("cursor")).intValue(),
                String.valueOf(m.get("state")),
                (Map<String, Object>) m.get("vars"),
                pending,
                ((Number) m.get("traceLength")).intValue(),
                ((Number) m.get("appliedLength")).intValue(),
                ((Number) m.get("rngState")).longValue(),
                ((Number) m.get("internalSeq")).longValue(),
                String.valueOf(m.get("traceHash")));
    }
}
