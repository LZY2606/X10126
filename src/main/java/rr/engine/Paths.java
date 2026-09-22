package rr.engine;

import rr.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** data 深拷贝（JSON 值树）与点路径读写。 */
public final class Paths {
    private Paths() {}

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) out.add(deepCopy(x));
            return out;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> copyData(Map<String, Object> d) {
        return (Map<String, Object>) deepCopy(d == null ? Map.of() : d);
    }

    static List<String> split(String path) {
        List<String> out = new ArrayList<>();
        for (String p : path.split("\\.")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        if (out.isEmpty()) throw new Eval.EvalException("路径为空");
        return out;
    }

    @SuppressWarnings("unchecked")
    public static void set(Map<String, Object> root, String path, Object value) {
        List<String> parts = split(path);
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.size() - 1; i++) {
            String k = parts.get(i);
            Object next = cur.get(k);
            if (!(next instanceof Map)) {
                Map<String, Object> nm = new LinkedHashMap<>();
                cur.put(k, nm);
                next = nm;
            }
            cur = (Map<String, Object>) next;
        }
        cur.put(parts.get(parts.size() - 1), value);
    }

    public static Object get(Map<String, Object> root, String path) {
        List<String> parts = split(path);
        Object cur = root;
        for (String p : parts) {
            if (!(cur instanceof Map)) throw new Eval.EvalException("路径不存在: " + path);
            cur = ((Map<?, ?>) cur).get(p);
            if (cur == null && !((Map<?, ?>) root).containsKey(p)) {
                // 仅用于存在性场景；引擎不直接调用
            }
        }
        return cur;
    }
}
