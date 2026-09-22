package replay;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable-by-convention machine snapshot: current state, variables, deterministic RNG cursor. */
public final class Snapshot {
    public String state;
    public final Map<String, Object> vars;
    public long rng;

    public Snapshot(String state, Map<String, Object> vars, long rng) {
        this.state = state;
        this.vars = vars;
        this.rng = rng;
    }

    public Snapshot copy() {
        return new Snapshot(state, new LinkedHashMap<>(vars), rng);
    }

    /** Deterministic LCG so a replay with the same seed always yields the same sequence. */
    public long nextRandom() {
        rng = rng * 6364136223846793005L + 1442695040888963407L;
        return (rng >>> 16) & 0x7fffffffL;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("vars", Json.deepCopy(vars));
        m.put("rng", rng);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Snapshot fromJson(Map<String, Object> m) {
        Object v = m.get("vars");
        Map<String, Object> vars = v instanceof Map ? (Map<String, Object>) Json.deepCopy(v) : new LinkedHashMap<>();
        long rng = m.get("rng") instanceof Number ? ((Number) m.get("rng")).longValue() : 0L;
        return new Snapshot((String) m.get("state"), vars, rng);
    }
}
