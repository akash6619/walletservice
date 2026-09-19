package com.walletservice.exception;

/** Indicates that an authenticated token subject is not an active registered user. */
public class UnregisteredCallerException extends RuntimeException {

    /** Creates the exception with the stable client-safe message. */
    public UnregisteredCallerException() {
        super("The authenticated user is not active or registered");
    }
}
