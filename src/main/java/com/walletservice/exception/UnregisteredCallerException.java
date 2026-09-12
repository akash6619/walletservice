package com.walletservice.exception;

public class UnregisteredCallerException extends RuntimeException {

    public UnregisteredCallerException() {
        super("The authenticated user is not active or registered");
    }
}
