package com.walletservice.exception;

/** Indicates reuse of an idempotency key for a different recipient or amount. */
public class IdempotencyConflictException extends RuntimeException {

    /** Creates the exception with the stable client-safe message. */
    public IdempotencyConflictException() {
        super("The idempotency key was already used with a different transfer body");
    }
}
