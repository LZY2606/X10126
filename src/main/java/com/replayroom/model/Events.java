package com.replayroom.model;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Helpers for the immutable event records carried by event logs, internal
 * queues, checkpoints and traces.
 *
 * External events are supplied by the user and carry id/time/source/seq/data.
 * Internal events are produced by actions; they share the same map shape but
 * have an internal=true marker and deterministic synthetic ids/seq values.
 *
 * Ordering of same-logical-time events is stable:
 *   time asc, sourcePriority asc (sources map, default priority 100),
 *   seq asc (original sequence number), id asc.
 * Internal events are processed depth-first before the next queued event.
 */
public final class Events {

    public static final String INTERNAL_SOURCE = "@internal";

    private Events() {
    }

    public static long time(Map<String, Object> event) {
        return asLong(event.get("time"));
    }

    public static String source(Map<String, Object> event) {
        Object value = event.get("source");
        return value == null ? "" : String.valueOf(value);
    }

    public static long seq(Map<String, Object> event) {
        return asLong(event.getOrDefault("seq", 0L));
    }

    public static String id(Map<String, Object> event) {
        Object value = event.get("id");
        return value == null ? "" : String.valueOf(value);
    }

    public static boolean internal(Map<String, Object> event) {
        return Boolean.TRUE.equals(event.get("internal"));
    }

    public static Map<String, Object> resolvePriority(Map<String, Object> definition) {
        Object sources = definition.get("sources");
        return sources instanceof Map<?, ?> map ? ModelAccess.asStringObjectMap(map) : Map.of();
    }

    public static long sourcePriority(String source, Map<String, Object> sources) {
        Object entry = sources.get(source);
        if (entry instanceof Map<?, ?> map) {
            Object priority = map.get("priority");
            if (priority instanceof Number number) {
                return number.longValue();
            }
        }
        return 100L;
    }

    public static Comparator<Map<String, Object>> externalComparator(Map<String, Object> sources) {
        return Comparator
                .comparingLong(Events::time)
                .thenComparingLong(event -> sourcePriority(source(event), sources))
                .thenComparingLong(Events::seq)
                .thenComparing(Events::id);
    }

    public static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value).trim());
    }
}
