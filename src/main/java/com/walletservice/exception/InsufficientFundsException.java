package com.walletservice.exception;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

    private final UUID transferId;

    public InsufficientFundsException(UUID transferId) {
        super("The wallet has insufficient funds for this transfer");
        this.transferId = transferId;
    }

    public UUID getTransferId() {
        return transferId;
    }
}
