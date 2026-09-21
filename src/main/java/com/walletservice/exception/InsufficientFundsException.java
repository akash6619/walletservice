package com.walletservice.exception;

import java.util.UUID;

/** Signals that a transfer was durably rejected because the sender lacked sufficient funds. */
public class InsufficientFundsException extends RuntimeException {

    private final UUID transferId;

    /** Creates the exception and retains the rejected transfer identifier for diagnostics. */
    public InsufficientFundsException(UUID transferId) {
        super("The wallet has insufficient funds for this transfer");
        this.transferId = transferId;
    }

    /** Creates an insufficient-funds error when no rejected transfer row is persisted. */
    public InsufficientFundsException() {
        super("The wallet has insufficient funds for this transfer");
        this.transferId = null;
    }

    /** Returns the identifier of the persisted rejected transfer. */
    public UUID getTransferId() {
        return transferId;
    }
}
