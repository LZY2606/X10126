package replayroom.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一个待处理/已处理的事件实例。排序键: (逻辑时间, 来源优先级, 原始序号, 入队序号, id)。 */
public class EventInstance {
    public String id;
    public long time;
    public String source;
    public long seq;
    public String event;
    public Map<String, Object> data = new LinkedHashMap<>();
    public long enqueueOrder;
    public boolean internal;

    public String positionKey() {
        return time + "|" + source + "|" + seq;
    }
}
