package com.walletservice.exception;

/** Indicates an impossible persistence or lifecycle state that requires operator attention. */
public class InvariantViolationException extends RuntimeException {

    /** Creates the exception with an internal diagnostic message. */
    public InvariantViolationException(String message) {
        super(message);
    }
}
