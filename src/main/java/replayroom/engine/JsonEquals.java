package replayroom.engine;

import java.util.List;
import java.util.Map;

final class JsonEquals {

    private JsonEquals() {}

    static boolean maps(Map<?, ?> a, Map<?, ?> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (Map.Entry<?, ?> entry : a.entrySet()) {
            if (!values(entry.getValue(), b.get(entry.getKey()))) return false;
        }
        return true;
    }

    static boolean values(Object a, Object b) {
        if (a == null || b == null) return a == null && b == null;
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) return maps(ma, mb);
        if (a instanceof List<?> la && b instanceof List<?> lb) return lists(la, lb);
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return a.equals(b);
    }

    private static boolean lists(List<?> a, List<?> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!values(a.get(i), b.get(i))) return false;
        }
        return true;
    }
}
