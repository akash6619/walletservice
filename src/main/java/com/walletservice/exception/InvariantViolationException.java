package com.walletservice.exception;

public class InvariantViolationException extends RuntimeException {

    public InvariantViolationException(String message) {
        super(message);
    }
}
