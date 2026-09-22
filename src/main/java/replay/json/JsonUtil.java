package replay.json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonUtil {
    private JsonUtil() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value, String name) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(name + " must be an object");
    }

    public static List<Object> list(Object value, String name) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        throw new IllegalArgumentException(name + " must be a list");
    }

    public static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(key + " is required and must be a non-empty string");
        }
        return string;
    }

    public static String optionalString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return string;
    }

    public static long integer(Object value, String name) {
        if (value instanceof Number number) {
            long result = number.longValue();
            if (number.doubleValue() != (double) result) {
                throw new IllegalArgumentException(name + " must be an integer");
            }
            return result;
        }
        throw new IllegalArgumentException(name + " must be an integer");
    }

    public static Map<String, Object> mutableMap() {
        return new LinkedHashMap<>();
    }

    public static List<Object> mutableList() {
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static String fingerprint(Object value) {
        return sha256(Json.canonical(value));
    }
}
