package com.wallet.exception;

public class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException() {
        super("wallet not found");
    }
}
