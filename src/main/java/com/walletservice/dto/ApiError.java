package com.walletservice.dto;

public record ApiError(
        String code,
        String message,
        boolean retryable,
        String correlationId
) {
}
