package replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EventCodec {
    private EventCodec() {}

    static Models.EventEnvelope parseExternal(Object json) {
        Map<String, Object> map = Json.object(json);
        Models.EventEnvelope event = fill(new Models.EventEnvelope(), map);
        if (event.id == null || event.id.isBlank()) throw new IllegalArgumentException("event id is required");
        if (event.type == null || event.type.isBlank()) throw new IllegalArgumentException("event type is required");
        if (event.source == null || event.source.isBlank()) throw new IllegalArgumentException("event source is required");
        event.internal = false;
        event.parentEventId = null;
        event.internalOrder = 0;
        return event;
    }

    static List<Models.EventEnvelope> parseExternals(Object json) {
        List<Object> values = Json.list(json);
        List<Models.EventEnvelope> events = new ArrayList<>();
        for (Object value : values) events.add(parseExternal(value));
        return events;
    }

    static Models.EventEnvelope fill(Models.EventEnvelope event, Map<String, Object> map) {
        event.id = Json.string(map, "id");
        event.time = Json.optionalLong(map, "time", 0L);
        event.source = Json.optionalString(map, "source", "external");
        event.sequence = Json.optionalLong(map, "sequence", 0L);
        event.type = Json.string(map, "type");
        event.payload = map.get("payload");
        event.internal = Boolean.TRUE.equals(map.get("internal"));
        event.parentEventId = map.get("parentEventId") == null ? null : String.valueOf(map.get("parentEventId"));
        event.internalOrder = (int) Json.optionalLong(map, "internalOrder", 0L);
        return event;
    }

    static Map<String, Object> write(Models.EventEnvelope event) {
        Map<String, Object> json = event.toIdentityJson();
        if (!event.internal) {
            json.remove("internal");
            json.remove("parentEventId");
            json.remove("internalOrder");
        }
        return json;
    }

    static Map<String, Object> external(Models.EventEnvelope event) {
        return event.toExternalJson();
    }
}
