package com.wallet.exception;

public class SelfTransferException extends RuntimeException {

    public SelfTransferException() {
        super("self transfers are not allowed");
    }
}
