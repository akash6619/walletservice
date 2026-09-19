package com.walletservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Validated JSON body for creating a transfer.
 * The idempotency key is restricted to printable ASCII so it is safe to log and propagate.
 */
public record CreateTransferRequest(
        @NotNull UUID toUser,
        @NotNull @Positive Long amountPaise,
        @NotBlank @Size(max = 128)
        @Pattern(regexp = "^[\\x20-\\x7E]+$", message = "must contain printable ASCII only")
        String idempotencyKey
) {
}
