package com.walletservice.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable persisted view of a transfer and its lifecycle state. */
public record Transfer(
        UUID transferId,
        UUID fromUser,
        UUID toUser,
        long amountPaise,
        String idempotencyKey,
        TransferStatus status,
        Long senderBalanceAfter,
        Instant createdAt
) {
}
