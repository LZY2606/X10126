package replayroom.core;

/** 检查点/分支的定义指纹与当前定义不一致。 */
public class VersionMismatch extends RuntimeException {
    public VersionMismatch(String message) {
        super(message);
    }
}
