package replayroom.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import replayroom.json.Json;
import replayroom.model.Envelope;

/** First group of events that make a branch merge ambiguous or incompatible. */
public record MergeConflict(String reason, Map<String, Object> aEvent, Map<String, Object> bEvent,
                            String detail) {

    public static MergeConflict content(Envelope a, Envelope b) {
        return new MergeConflict("content-mismatch", a.toMap(), b.toMap(),
                "Same event id exists in both branches with different content");
    }

    public static MergeConflict order(Envelope a, Envelope b) {
        return new MergeConflict("order-conflict", a.toMap(), b.toMap(),
                "Relative order of events shared by both branches differs");
    }

    public static MergeConflict ambiguous(Envelope a, Envelope b) {
        return new MergeConflict("ambiguous-order", a.toMap(), b.toMap(),
                "Events at the same logical time and source priority appear in different branches; "
                        + "their cross-branch order is not deterministic");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reason", reason);
        map.put("detail", detail);
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("a", aEvent);
        group.put("b", bEvent);
        map.put("firstConflict", group);
        return map;
    }
}
