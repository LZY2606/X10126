package replayroom.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import replayroom.util.Json;

import java.util.List;

/** 会话导出/导入：导出内容包含定义、种子、初始状态与全部外部事件，
 *  导入后按相同步数重放，轨迹哈希必须一致。 */
public final class Transfer {
    public static final String FORMAT = "replay-room-export-v1";

    private Transfer() {
    }

    public static ObjectNode export(Session s) {
        ObjectNode out = Json.M.createObjectNode();
        out.put("format", FORMAT);
        out.put("name", s.name);
        out.set("definition", s.def.raw);
        out.put("seed", s.engine.seed);
        out.put("steps", s.engine.trace.size());
        out.put("fingerprint", s.fingerprint);
        out.put("traceHash", s.engine.traceHash());
        out.set("events", Json.M.valueToTree(s.engine.importedExternal));
        return out;
    }

    public static ImportResult importSession(Store store, JsonNode payload, String forcedId) {
        if (!FORMAT.equals(payload.path("format").asText())) {
            throw new IllegalArgumentException("无法识别的导出格式: " + payload.path("format").asText());
        }
        Definition def = Definition.parse(payload.get("definition"));
        long seed = payload.path("seed").asLong();
        int steps = payload.path("steps").asInt(Integer.MAX_VALUE);
        String name = payload.path("name").asText("imported");
        Session s = Session.create(forcedId != null ? forcedId : store.newId(), name, def, seed);
        List<EventInstance> events = Json.M.convertValue(payload.get("events"),
                new com.fasterxml.jackson.core.type.TypeReference<List<EventInstance>>() {
                });
        s.engine.importEvents(events);
        s.engine.runLimit(steps, null);
        store.put(s);
        ImportResult result = new ImportResult();
        result.session = s;
        result.expectedTraceHash = payload.path("traceHash").asText(null);
        result.actualTraceHash = s.engine.traceHash();
        result.matches = result.expectedTraceHash == null
                || result.expectedTraceHash.equals(result.actualTraceHash);
        return result;
    }

    public static class ImportResult {
        public Session session;
        public String expectedTraceHash;
        public String actualTraceHash;
        public boolean matches;
    }
}
