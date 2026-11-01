package io.emonlens.common;

import lombok.Data;
import lombok.ToString;
import org.slf4j.MDC;

@Data
public class ApiResult<T> {

    private int code;
    private String message;
    /** 可能含一次性明文凭据（ingest key / open token），禁止进入日志 */
    @ToString.Exclude
    private T data;
    private String traceId;

    public static <T> ApiResult<T> ok(T data) {
        ApiResult<T> result = new ApiResult<>();
        result.code = ErrorCode.OK.getCode();
        result.message = "ok";
        result.data = data;
        result.traceId = MDC.get("traceId");
        return result;
    }

    public static <T> ApiResult<T> error(ErrorCode errorCode, String message) {
        ApiResult<T> result = new ApiResult<>();
        result.code = errorCode.getCode();
        result.message = message;
        result.traceId = MDC.get("traceId");
        return result;
    }
}
