package com.walletservice.exception;

/** Indicates that a transfer recipient is unknown or inactive. */
public class RecipientNotFoundException extends RuntimeException {

    /** Creates the exception with the stable client-safe message. */
    public RecipientNotFoundException() {
        super("The recipient is not active or registered");
    }
}
