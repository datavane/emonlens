package io.emonlens.common;

import lombok.Getter;

@Getter
public enum ErrorCode {

    OK(0, "ok"),
    BAD_REQUEST(40000, "bad request"),
    UNAUTHORIZED(40101, "invalid or revoked ingest key"),
    FORBIDDEN(40301, "project out of scope"),
    NOT_FOUND(40401, "resource not found"),
    CONFLICT(40901, "conflict with current state"),
    INTERNAL_ERROR(50000, "internal error"),
    STORAGE_UNAVAILABLE(50001, "storage unavailable"),
    DOWNSTREAM_UNAVAILABLE(50002, "downstream unavailable"),
    RATE_LIMITED(50301, "rate limited");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

}
