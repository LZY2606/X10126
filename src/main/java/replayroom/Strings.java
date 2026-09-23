package replayroom;

final class Strings {
    private Strings() {
    }

    static String require(Object value, String message) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return String.valueOf(value);
    }

    static String stringOrDefault(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
