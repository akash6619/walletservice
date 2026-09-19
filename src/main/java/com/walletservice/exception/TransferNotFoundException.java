package com.walletservice.exception;

/** Indicates that a transfer is absent or intentionally hidden from the requesting caller. */
public class TransferNotFoundException extends RuntimeException {

    /** Creates the exception with the stable non-disclosing message. */
    public TransferNotFoundException() {
        super("Transfer not found");
    }
}
