package com.wallet.exception;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

    private final UUID transferId;
    private final long balancePaise;

    public InsufficientFundsException(UUID transferId, long balancePaise) {
        super("insufficient funds");
        this.transferId = transferId;
        this.balancePaise = balancePaise;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public long getBalancePaise() {
        return balancePaise;
    }
}
