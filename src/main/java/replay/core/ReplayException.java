package replay.core;

import java.util.Map;

/** 业务异常：携带 HTTP 状态码与结构化细节（如合并冲突信息）。 */
public final class ReplayException extends RuntimeException {
    public final int status;
    public final Map<String, Object> details;

    public ReplayException(int status, String message) {
        this(status, message, null);
    }

    public ReplayException(int status, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.details = details;
    }
}
