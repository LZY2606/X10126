package replayroom.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 轨迹条目：一次事件处理的前后快照与结果。 */
public class TraceEntry {
    public long index;
    public String eventId;
    public String event;
    public long time;
    public String source;
    public long seq;
    public boolean internal;
    public String result;
    public int ruleIndex;
    public String beforeState;
    public Map<String, Object> beforeVars = new LinkedHashMap<>();
    public String afterState;
    public Map<String, Object> afterVars = new LinkedHashMap<>();
    public List<String> emitted = new ArrayList<>();
    public String failure;
}
