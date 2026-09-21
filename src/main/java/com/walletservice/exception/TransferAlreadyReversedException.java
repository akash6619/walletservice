package com.walletservice.exception;

/** Indicates that another idempotency key has already reversed the original transfer. */
public class TransferAlreadyReversedException extends RuntimeException {

    public TransferAlreadyReversedException() {
        super("Transfer has already been reversed");
    }
}
