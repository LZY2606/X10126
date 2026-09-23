package replayroom;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class MergeConflictException extends RuntimeException {
    final Map<String, Object> details;

    MergeConflictException(String reason, String orderGroup, List<Map<String, Object>> events) {
        super(reason);
        details = new LinkedHashMap<>();
        details.put("error", "merge_conflict");
        details.put("reason", reason);
        details.put("orderGroup", orderGroup);
        details.put("conflictEvents", events);
    }
}
