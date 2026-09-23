package replay.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** A named snapshot of an engine, pinned to the definition fingerprint at creation time. */
public final class Checkpoint {
    public String id;
    public String name;
    public String defFingerprint;
    public Map<String, Object> engineSnapshot;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("defFingerprint", defFingerprint);
        m.put("engine", engineSnapshot);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Checkpoint fromMap(Map<String, Object> m) {
        Checkpoint c = new Checkpoint();
        c.id = EventInstance.str(m.get("id"));
        c.name = EventInstance.str(m.get("name"));
        c.defFingerprint = EventInstance.str(m.get("defFingerprint"));
        c.engineSnapshot = (Map<String, Object>) m.get("engine");
        return c;
    }
}
