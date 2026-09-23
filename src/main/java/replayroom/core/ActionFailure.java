package replayroom.core;

/** 动作执行失败：触发当前事件的状态与派生事件回滚，但失败记录进入轨迹。 */
public class ActionFailure extends RuntimeException {
    public ActionFailure(String message) {
        super(message);
    }
}
