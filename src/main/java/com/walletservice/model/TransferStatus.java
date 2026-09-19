package com.walletservice.model;

/** Terminal and in-transaction states of a transfer. */
public enum TransferStatus {
    /** Initial state held while the owning transaction performs the balance movement. */
    PROCESSING,
    /** Funds were atomically debited and credited. */
    APPLIED,
    /** The sender did not have enough funds; no balance was changed. */
    REJECTED_INSUFFICIENT_FUNDS
}
