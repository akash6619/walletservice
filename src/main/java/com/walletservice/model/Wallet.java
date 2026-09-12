package com.walletservice.model;

import java.time.Instant;
import java.util.UUID;

public record Wallet(
        UUID walletId,
        UUID userId,
        long balancePaise,
        Instant createdAt,
        Instant updatedAt
) {
}
