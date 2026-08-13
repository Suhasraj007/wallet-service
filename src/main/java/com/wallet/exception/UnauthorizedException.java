package com.wallet.exception;

public class UnauthorizedException extends RuntimeException {

    private final String reason;

    public UnauthorizedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
