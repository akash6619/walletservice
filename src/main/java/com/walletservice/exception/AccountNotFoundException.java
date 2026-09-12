package com.walletservice.exception;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException() {
        super("Wallet account not found");
    }
}
