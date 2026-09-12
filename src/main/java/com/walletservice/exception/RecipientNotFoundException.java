package com.walletservice.exception;

public class RecipientNotFoundException extends RuntimeException {

    public RecipientNotFoundException() {
        super("The recipient is not active or registered");
    }
}
