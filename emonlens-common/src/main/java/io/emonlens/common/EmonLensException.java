package io.emonlens.common;

import lombok.Getter;

@Getter
public class EmonLensException extends RuntimeException {

    private final ErrorCode errorCode;

    public EmonLensException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public EmonLensException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public EmonLensException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public EmonLensException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }
}
