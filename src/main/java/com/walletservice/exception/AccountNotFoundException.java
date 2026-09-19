package com.walletservice.exception;

/** Indicates that an active caller has not provisioned a wallet account. */
public class AccountNotFoundException extends RuntimeException {

    /** Creates the exception with the stable client-safe message. */
    public AccountNotFoundException() {
        super("Wallet account not found");
    }
}
