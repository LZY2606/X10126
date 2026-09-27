package replay.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deep copy / clone helpers over JSON-shaped values. */
public final class Util {
    private Util() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            copy.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    static Object deepCopyValue(Object v) {
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(e.getKey().toString(), deepCopyValue(e.getValue()));
            }
            return copy;
        }
        if (v instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(deepCopyValue(item));
            return copy;
        }
        return v;
    }
}
