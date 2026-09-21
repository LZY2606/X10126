package com.replayroom.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed access helpers for the JSON-backed domain objects. */
public final class ModelAccess {

    private ModelAccess() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected object, got " + value.getClass().getSimpleName());
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("expected array, got " + value.getClass().getSimpleName());
        }
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> asObjectList(Object value) {
        List<Object> raw = asList(value);
        List<Map<String, Object>> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            result.add(asObject(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asStringObjectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    public static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public static Map<String, Object> objectField(Map<String, Object> map, String key) {
        return asObject(map.get(key));
    }

    public static List<Map<String, Object>> objectListField(Map<String, Object> map, String key) {
        return asObjectList(map.get(key));
    }
}
