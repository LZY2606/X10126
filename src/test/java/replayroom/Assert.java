package replayroom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Assert {

    public static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) throw new AssertionError(message);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " expected=<" + expected + "> but was=<" + actual + ">");
        }
    }

    public static void assertEqualsDeep(Object expected, Object actual, String message) {
        if (!deepEquals(expected, actual)) {
            throw new AssertionError(message + "\nexpected=" + expected + "\nactual=  " + actual);
        }
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    public static void assertContains(String haystack, String needle, String message) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError(message + " (missing '" + needle + "' in '" + haystack + "')");
        }
    }

    private static boolean deepEquals(Object a, Object b) {
        if (a == null || b == null) return a == null && b == null;
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (!ma.keySet().equals(mb.keySet())) return false;
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!deepEquals(e.getValue(), mb.get(e.getKey()))) return false;
            }
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) if (!deepEquals(la.get(i), lb.get(i))) return false;
            return true;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return a.equals(b);
    }

    public static List<Object> list(Object... values) {
        List<Object> list = new ArrayList<>();
        for (Object value : values) list.add(value);
        return list;
    }
}
