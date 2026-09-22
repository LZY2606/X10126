package replay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Models {
    private Models() {}

    static final class Definition {
        String version = "1";
        String initialState = "idle";
        long seed = 1L;
        Map<String, Object> initialVariables = new LinkedHashMap<>();
        Map<String, Integer> sourcePriority = new LinkedHashMap<>();
        List<Transition> transitions = new ArrayList<>();
    }

    static final class Transition {
        String name;
        String from;
        String event;
        String condition;
        String to;
        List<Action> actions = new ArrayList<>();
    }

    static sealed interface Action permits SetAction, OutputAction, EmitAction, RandomIntAction, FailAction {
        String type();
        Map<String, Object> toJson();
    }

    record SetAction(String target, String value) implements Action {
        public String type() { return "set"; }
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("type", "set");
            json.put("target", target);
            json.put("value", value);
            return json;
        }
    }

    record OutputAction(String name, String payload) implements Action {
        public String type() { return "output"; }
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("type", "output");
            json.put("name", name);
            if (payload != null) json.put("payload", payload);
            return json;
        }
    }

    record EmitAction(String event, long timeDelta, String source, long sequence, String payload) implements Action {
        public String type() { return "emit"; }
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("type", "emit");
            json.put("event", event);
            json.put("timeDelta", timeDelta);
            json.put("source", source);
            json.put("sequence", sequence);
            if (payload != null) json.put("payload", payload);
            return json;
        }
    }

    record RandomIntAction(String target, String min, String max) implements Action {
        public String type() { return "randomInt"; }
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("type", "randomInt");
            json.put("target", target);
            json.put("min", min);
            json.put("max", max);
            return json;
        }
    }

    record FailAction(String error, String payload) implements Action {
        public String type() { return "fail"; }
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("type", "fail");
            json.put("error", error);
            if (payload != null) json.put("payload", payload);
            return json;
        }
    }

    static final class EventEnvelope {
        String id;
        long time;
        String source;
        long sequence;
        String type;
        Object payload;
        boolean internal;
        String parentEventId;
        int internalOrder;

        Map<String, Object> toIdentityJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("time", time);
            json.put("source", source);
            json.put("sequence", sequence);
            json.put("type", type);
            json.put("payload", payload);
            json.put("internal", internal);
            json.put("parentEventId", parentEventId);
            json.put("internalOrder", internalOrder);
            return json;
        }

        Map<String, Object> toExternalJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("time", time);
            json.put("source", source);
            json.put("sequence", sequence);
            json.put("type", type);
            json.put("payload", payload);
            return json;
        }
    }

    static final class Checkpoint {
        String id;
        String branchId;
        int step;
        String parentCheckpointId;
        String definitionFingerprint;
        String stateHash;
        String traceHash;
        String state;
        Map<String, Object> variables = new LinkedHashMap<>();
        long randomState;
        List<EventEnvelope> internalQueue = new ArrayList<>();
    }

    static final class Branch {
        String id;
        String name;
        String baseCheckpointId;
        List<EventEnvelope> events = new ArrayList<>();
        int cursor;
        List<StepRecord> trace = new ArrayList<>();
        String headCheckpointId;
    }

    static final class StepRecord {
        int step;
        EventEnvelope event;
        boolean matched;
        String transition;
        String fromState;
        String toState;
        boolean failed;
        String error;
        Map<String, Object> beforeSnapshot = new LinkedHashMap<>();
        Map<String, Object> afterSnapshot = new LinkedHashMap<>();
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> outputs = new ArrayList<>();
        List<EventEnvelope> emitted = new ArrayList<>();
        String beforeHash;
        String afterHash;
        String traceHash;
        String stateHash;
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> cloneJson(Object value) {
        Object copy = Json.parse(Json.write(value));
        if (!(copy instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) copy;
        return map;
    }
}
