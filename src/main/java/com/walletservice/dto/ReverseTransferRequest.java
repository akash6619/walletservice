package com.walletservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The only client-controlled value for a reversal; money and participants come from the original. */
public record ReverseTransferRequest(
        @NotBlank @Size(max = 128)
        @Pattern(regexp = "^[\\x20-\\x7E]+$", message = "must contain printable ASCII only")
        String idempotencyKey
) {
}
