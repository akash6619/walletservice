package com.walletservice.exception;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException() {
        super("Transfer not found");
    }
}
