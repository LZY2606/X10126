package replay;

import java.util.List;
import java.util.Map;

public final class Asserts {

    private Asserts() {
    }

    public static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    public static void assertEquals(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void assertFalse(boolean cond, String msg) {
        if (cond) {
            throw new AssertionError(msg);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> step(Map<String, Object> runResult, int index) {
        return (Map<String, Object>) ((List<Object>) runResult.get("steps")).get(index);
    }

    public static String fp(Map<String, Object> step) {
        return String.valueOf(step.get("traceHash"));
    }
}

