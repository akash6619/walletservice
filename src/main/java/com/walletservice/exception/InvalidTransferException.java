package com.walletservice.exception;

/** Indicates that a transfer command violates a domain-level input rule. */
public class InvalidTransferException extends RuntimeException {

    /** Creates the exception with a specific client-safe validation message. */
    public InvalidTransferException(String message) {
        super(message);
    }
}
