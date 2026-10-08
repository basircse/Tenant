package com.revesoft.tms.common;

import org.springframework.http.HttpStatus;

/** A business rule was violated. Mapped to HTTP 400 (or the given status) with the message. */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    /** Optional machine-readable code (e.g. LICENSE_EXPIRED) so apps can react precisely. */
    private final String code;

    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    public BusinessException(HttpStatus status, String message) {
        this(status, null, message);
    }

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, message);
    }

    public static BusinessException notFound(String what) {
        return new BusinessException(HttpStatus.NOT_FOUND, what + " not found");
    }
}
