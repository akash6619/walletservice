package com.walletservice.dto;

/** Stable JSON error envelope returned by controller and security failures. */
public record ApiError(
        String code,
        String message,
        boolean retryable,
        String correlationId
) {
}
