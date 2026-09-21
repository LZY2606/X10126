package replayroom.engine;

import replayroom.model.TraceEntry;

public record MergeResult(Session session, TraceEntry lastEntry, String traceHash) {
}
