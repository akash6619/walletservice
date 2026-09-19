package com.walletservice.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable snapshot of a user's wallet, with its balance represented in paise. */
public record Wallet(
        UUID walletId,
        UUID userId,
        long balancePaise,
        Instant createdAt,
        Instant updatedAt
) {
}
