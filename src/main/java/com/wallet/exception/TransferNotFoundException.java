package com.wallet.exception;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException() {
        super("transfer not found");
    }
}
