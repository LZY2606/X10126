package replay.core;

import replay.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 回放会话：锁定定义版本、初始状态、事件内容与随机种子。 */
public final class Session {
    String id;
    String name;
    Definition definition;
    long seed;
    long nextEventSeq;                       // 会话级事件序号分配器
    final List<Event> importedEvents = new ArrayList<>();   // 会话级导入记录（排序后）
    final Map<String, Branch> branches = new LinkedHashMap<>();

    /** 会话指纹：定义指纹 + 初始状态 + 事件内容 + 随机种子 一起锁定。 */
    String fingerprint() {
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("definition", definition.fingerprint());
        lock.put("initial", definition.initial);
        lock.put("seed", Long.toUnsignedString(seed));
        List<Object> evs = new ArrayList<>();
        List<Event> sorted = new ArrayList<>(importedEvents);
        sorted.sort(Event.order(definition));
        for (Event e : sorted) evs.add(e.toJson());
        lock.put("events", evs);
        return Hashes.sha256(Json.writeCanonical(lock));
    }

    Map<String, Object> toJson(boolean withTrace) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("definition", definition.raw);
        m.put("definitionFingerprint", definition.fingerprint());
        m.put("seed", Long.toUnsignedString(seed));
        m.put("nextEventSeq", nextEventSeq);
        List<Object> evs = new ArrayList<>();
        for (Event e : importedEvents) evs.add(e.toJson());
        m.put("events", evs);
        m.put("fingerprint", fingerprint());
        Map<String, Object> bs = new LinkedHashMap<>();
        for (Map.Entry<String, Branch> e : branches.entrySet()) {
            bs.put(e.getKey(), e.getValue().toJson(withTrace));
        }
        m.put("branches", bs);
        return m;
    }

    static Session fromJson(Map<String, Object> m) {
        Session s = new Session();
        s.id = Json.asString(m.get("id"), "session.id");
        s.name = m.containsKey("name") ? Json.asString(m.get("name"), "session.name") : s.id;
        s.definition = new Definition(Json.asMap(m.get("definition"), "session.definition"));
        s.seed = Long.parseUnsignedLong(Json.asString(m.get("seed"), "session.seed"));
        s.nextEventSeq = Json.asLong(m.get("nextEventSeq"), "session.nextEventSeq");
        for (Object e : Json.asList(m.get("events"), "session.events")) {
            s.importedEvents.add(Event.fromJson(Json.asMap(e, "event")));
        }
        Map<String, Object> bs = Json.asMap(m.get("branches"), "session.branches");
        for (Map.Entry<String, Object> e : bs.entrySet()) {
            Branch b = Branch.fromJson(Json.asMap(e.getValue(), "branch"));
            s.branches.put(b.name, b);
        }
        return s;
    }
}
